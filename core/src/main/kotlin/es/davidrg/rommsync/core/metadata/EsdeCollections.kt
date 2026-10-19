package es.davidrg.rommsync.core.metadata

import es.davidrg.rommsync.core.collections.CollectionMember
import es.davidrg.rommsync.core.collections.DeviceCollection
import es.davidrg.rommsync.core.collections.LauncherCollectionWriter
import es.davidrg.rommsync.core.collections.LauncherGameRemover
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/*
 * RomM collections as ES-DE custom collections (ES-DE 3.x).
 *
 * ES-DE format (USERGUIDE "Custom collections"; es-app/src/CollectionSystemsManager.cpp):
 *  - one file per collection, `<ES-DE>/collections/custom-<Name>.cfg`, one game per line as
 *    `%ROMPATH%/<system>/<path>` (absolute paths are accepted too); only files, or folders
 *    interpreted as files (`Game.m3u/`), can be members;
 *  - a collection is shown only when its name is listed in `es_settings.xml`
 *    `<string name="CollectionSystemsCustom" value="a,b" />`; names are matched
 *    case-insensitively against other files, cannot contain `*",./:;<>\|`, and a name equal
 *    to a system's theme folder (`snes`, …) is skipped by ES-DE;
 *  - with "Group custom collections" (CollectionCustomGrouping) the collections appear as
 *    folders of the "Collections" system. Their description ("This collection contains N
 *    games …") and image (a random member) are generated at runtime
 *    (`updateCollectionFolderMetadata`); ES-DE reads no per-collection description or artwork
 *    file, only a theme can style a collection (by its lower-case name). The RomM
 *    description and cover are therefore not written.
 *
 * ES-DE rewrites `es_settings.xml` itself (at startup and on menu changes) and the collection
 * files when a collection is edited in its UI, so every change here goes through an
 * [EsdeEditor], which runs it only while ES-DE is not running (Steam Deck daemon:
 * `LauncherRefreshScheduler`; Android: `EsdeReloader`).
 *
 * Local overrides: games the user adds to a collection in ES-DE are kept (only lines this
 * client wrote are managed); a collection the user disabled in ES-DE stays disabled
 * (it is enabled once, when created); a collection is deleted on unsubscribe only when this
 * client created it and the user added nothing to it.
 */

/** Queues a change to ES-DE's own files; it runs only while ES-DE is not running. */
fun interface EsdeEditor {
    fun submit(description: String, edit: () -> Unit)
}

/** `CollectionSystemsCustom` in ES-DE's `es_settings.xml` (no root element: edited line-wise). */
object EsdeSettings {
    private const val KEY = "CollectionSystemsCustom"
    private val SETTING = Regex("""<string\s+name="$KEY"\s+value="([^"]*)"\s*/>""")

    fun file(esdeDir: File): File = File(File(esdeDir, "settings"), "es_settings.xml")

    fun customCollections(text: String): List<String> =
        SETTING.find(text)?.groupValues?.get(1)?.let { EsdeGamelist.decode(it) }
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** [text] with the setting set to [names] (added at the end when absent). */
    fun withCustomCollections(text: String, names: List<String>): String {
        val line = """<string name="$KEY" value="${attr(names.joinToString(","))}" />"""
        val match = SETTING.find(text)
        if (match != null) return text.replaceRange(match.range, line)
        val base = text.ifBlank { "<?xml version=\"1.0\"?>\n" }
        return base + (if (base.endsWith("\n")) "" else "\n") + line + "\n"
    }

    /** Applies [change] to the enabled list; writes only when it changed. */
    fun update(esdeDir: File, change: (List<String>) -> List<String>) {
        val file = file(esdeDir)
        val text = if (file.isFile) file.readText(Charsets.UTF_8) else ""
        val current = customCollections(text)
        val next = change(current).distinct()
        if (next == current) return
        writeAtomically(file, withCustomCollections(text, next))
    }

    private fun attr(value: String): String = EsdeGamelist.escape(value).replace("\"", "&quot;")
}

/** What this client wrote per RomM collection (`esde-collections.properties`). */
class EsdeCollectionLedger(private val file: File) {
    data class Entry(val name: String, val created: Boolean, val lines: Set<String>, val hash: String)

    private val props = Properties()

    init {
        if (file.isFile) runCatching { file.inputStream().use { props.load(it) } }
    }

    @Synchronized
    fun get(key: String): Entry? {
        val name = props.getProperty("$key.name") ?: return null
        return Entry(
            name = name,
            created = props.getProperty("$key.created") == "true",
            lines = props.getProperty("$key.lines").orEmpty().split('\n').filter { it.isNotEmpty() }.toSet(),
            hash = props.getProperty("$key.hash").orEmpty(),
        )
    }

    @Synchronized
    fun all(): Map<String, Entry> = props.stringPropertyNames()
        .filter { it.endsWith(".name") }
        .map { it.removeSuffix(".name") }
        .mapNotNull { key -> get(key)?.let { key to it } }
        .toMap()

    @Synchronized
    fun put(key: String, entry: Entry) {
        props["$key.name"] = entry.name
        props["$key.created"] = entry.created.toString()
        props["$key.lines"] = entry.lines.sorted().joinToString("\n")
        props["$key.hash"] = entry.hash
        persist()
    }

    @Synchronized
    fun remove(key: String) {
        listOf("name", "created", "lines", "hash").forEach { props.remove("$key.$it") }
        persist()
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.outputStream().use { props.store(it, "RomM Sync: ES-DE collections written by this client") }
        moveIntoPlace(tmp, file)
    }
}

/** Collection file lines for ES-DE: `%ROMPATH%/…` under ES-DE's ROM directory, else absolute. */
class EsdeRomPaths(private val esdeRomDirectory: () -> String?) {

    /** The line for a member: its ES-DE game entry (file, `.m3u` folder, or launch file inside a game folder). */
    fun lineFor(member: CollectionMember): String? {
        val top = EsdeRemoval.topLevel(member.platformFolder, member.files) ?: return null
        val ref = EsdeMetadata.gameRef(top) ?: return null
        return render(File(top.absoluteFile.parentFile, ref.path.removePrefix("./")))
    }

    /** Lines that point at [top] or anything inside it, in both notations. */
    fun prefixesFor(top: File): List<String> =
        listOfNotNull(render(top.absoluteFile), top.absoluteFile.normalize().path.replace(File.separatorChar, '/')).distinct()

    fun render(file: File): String {
        val path = file.absoluteFile.normalize().path.replace(File.separatorChar, '/')
        val root = esdeRomDirectory()?.let { File(it).absoluteFile.normalize().path.replace(File.separatorChar, '/').trimEnd('/') }
        return if (root != null && path.startsWith("$root/")) "%ROMPATH%/" + path.removePrefix("$root/") else path
    }
}

/**
 * [LauncherCollectionWriter] for ES-DE custom collections (see the file comment for the
 * format and the override rules). [write]/[remove] only queue the change on [editor].
 */
class EsdeCollectionWriter(
    private val esdeDir: () -> File?,
    private val romsRoot: () -> String?,
    private val paths: EsdeRomPaths,
    private val ledger: EsdeCollectionLedger,
    private val editor: EsdeEditor,
    private val log: (String) -> Unit = {},
) : LauncherCollectionWriter {

    override fun write(collection: DeviceCollection, members: List<CollectionMember>, cover: File?) {
        // Paths are resolved now, while the files are known to be on disk.
        val lines = members.mapNotNull { paths.lineFor(it) }.distinct().sorted()
        val dir = esdeDir() ?: return log("collections: no ES-DE folder; ${collection.name} not written")
        editor.submit("collection ${collection.name}") { apply(dir, collection, lines) }
    }

    override fun remove(key: String, name: String) {
        val dir = esdeDir() ?: return
        editor.submit("remove collection $name") { removeNow(dir, key) }
    }

    /** Writes the collection file and enables it; runs with ES-DE closed. */
    internal fun apply(esdeDir: File, collection: DeviceCollection, desired: List<String>) {
        val collectionsDir = File(esdeDir, "collections")
        if (!collectionsDir.isDirectory && !collectionsDir.mkdirs()) error("cannot create ${collectionsDir.path}")
        val key = collection.key
        var entry = ledger.get(key)
        val name = esdeName(collection, systemFolders())

        // An adopted file keeps the user's spelling; only a real rename (or one of ours) moves it.
        if (entry != null && entry.name != name && (entry.created || !entry.name.equals(name, ignoreCase = true))) {
            entry = renameOrRelease(esdeDir, collectionsDir, key, entry, name)
        }

        val existing = findFile(collectionsDir, name)
        val file = existing ?: File(collectionsDir, "custom-$name.cfg")
        val actualName = file.name.removePrefix("custom-").removeSuffix(".cfg")
        val created = entry?.created ?: (existing == null)
        val before = if (file.isFile) readLines(file) else emptyList()
        val previous = entry?.lines.orEmpty()
        val foreign = before.filter { it !in previous }
        val merged = foreign + desired.filter { it !in foreign }
        val text = merged.joinToString("") { "$it\n" }
        if (!file.isFile || file.readText(Charsets.UTF_8) != text) writeAtomically(file, text)
        if (entry == null && existing == null) {
            // Enabled once, when this client creates it; a user who disables it in ES-DE keeps it off.
            EsdeSettings.update(esdeDir) { it + actualName }
        }
        ledger.put(key, EsdeCollectionLedger.Entry(actualName, created, (desired - foreign.toSet()).toSet(), sha256(text)))
    }

    /** Collection renamed in RomM: move our file (and its enabled entry) or let go of an adopted one. */
    private fun renameOrRelease(
        esdeDir: File,
        collectionsDir: File,
        key: String,
        entry: EsdeCollectionLedger.Entry,
        newName: String,
    ): EsdeCollectionLedger.Entry? {
        val old = File(collectionsDir, "custom-${entry.name}.cfg")
        val clash = findFile(collectionsDir, newName)?.takeIf { it.name != old.name }
        if (entry.created && old.isFile && clash == null) {
            val renamed = File(collectionsDir, "custom-$newName.cfg")
            if (old.name.equals(renamed.name, ignoreCase = true)) {
                // Case-only rename: go through a temporary name (case-insensitive file systems).
                val tmp = File(collectionsDir, ".romm-rename-${old.name}")
                moveIntoPlace(old, tmp)
                moveIntoPlace(tmp, renamed)
            } else {
                moveIntoPlace(old, renamed)
            }
            EsdeSettings.update(esdeDir) { names -> names.map { if (it == entry.name) newName else it } }
            val moved = entry.copy(name = newName)
            ledger.put(key, moved)
            return moved
        }
        stripOrDelete(esdeDir, old, entry)
        ledger.remove(key)
        return null
    }

    internal fun removeNow(esdeDir: File, key: String) {
        val entry = ledger.get(key) ?: return
        stripOrDelete(esdeDir, File(File(esdeDir, "collections"), "custom-${entry.name}.cfg"), entry)
        ledger.remove(key)
    }

    /** Deletes a file this client created when only its lines are left; else removes just its lines. */
    private fun stripOrDelete(esdeDir: File, file: File, entry: EsdeCollectionLedger.Entry) {
        if (!file.isFile) return
        val foreign = readLines(file).filter { it !in entry.lines }
        if (entry.created && foreign.isEmpty()) {
            file.delete()
            EsdeSettings.update(esdeDir) { names -> names.filterNot { it == entry.name } }
            log("collections: removed ES-DE collection ${entry.name}")
        } else {
            writeAtomically(file, foreign.joinToString("") { "$it\n" })
            log("collections: kept ES-DE collection ${entry.name} (user games or not created by RomM Sync)")
        }
    }

    /** ES-DE treats system folders as theme folders; collections named like one are skipped. */
    private fun systemFolders(): Set<String> =
        romsRoot()?.let { File(it).listFiles() }.orEmpty().filter { it.isDirectory }.map { it.name.lowercase() }.toSet()

    companion object {
        private val FORBIDDEN = Regex("""[*",./:;<>\\|\u0000-\u001F]""")

        /** The ES-DE collection name for [collection]: RomM's name minus characters ES-DE drops. */
        fun esdeName(collection: DeviceCollection, systemFolders: Set<String>): String {
            val base = FORBIDDEN.replace(collection.name, "").replace(Regex("\\s+"), " ").trim()
                .ifEmpty { "RomM ${collection.kind} ${collection.id}" }
            return if (base.lowercase() in systemFolders) "$base (RomM)" else base
        }

        /** The existing `custom-<name>.cfg`, matched case-insensitively like ES-DE does. */
        fun findFile(collectionsDir: File, name: String): File? {
            val wanted = "custom-$name.cfg"
            return collectionsDir.listFiles()?.firstOrNull { it.isFile && it.name.equals(wanted, ignoreCase = true) }
        }

        fun readLines(file: File): List<String> =
            file.readText(Charsets.UTF_8).split('\n').map { it.removeSuffix("\r") }.filter { it.isNotBlank() }
    }
}

/**
 * [LauncherGameRemover] for ES-DE: once a rom's files are gone, drops its gamelist entry,
 * its media and its lines in every custom collection (what ES-DE's own Delete does).
 * Queued on [editor], which also restarts an idle ES-DE so it forgets the game.
 */
class EsdeGameRemover(
    private val target: () -> EsdeTarget?,
    private val paths: EsdeRomPaths,
    private val ledger: EsdeCollectionLedger,
    private val editor: EsdeEditor,
    private val log: (String) -> Unit = {},
) : LauncherGameRemover {

    override fun onRomRemoved(romId: Int, platformFolder: String, files: List<File>) {
        val top = EsdeRemoval.topLevel(platformFolder, files) ?: return
        val esde = target() ?: return
        editor.submit("remove rom $romId") { removeNow(esde, platformFolder, top) }
    }

    internal fun removeNow(esde: EsdeTarget, platformFolder: String, top: File) {
        EsdeRemoval.removeGamelistEntries(esde.esdeDir, platformFolder, top)
        val media = EsdeRemoval.deleteMedia(esde.mediaDir, platformFolder, top)
        val prefixes = paths.prefixesFor(top)
        fun matches(line: String) = prefixes.any { line == it || line.startsWith("$it/") }
        val collectionsDir = File(esde.esdeDir, "collections")
        val touched = mutableListOf<String>()
        collectionsDir.listFiles()?.filter { it.isFile && it.name.startsWith("custom-") && it.name.endsWith(".cfg") }?.forEach { cfg ->
            val lines = EsdeCollectionWriter.readLines(cfg)
            val kept = lines.filterNot(::matches)
            if (kept.size != lines.size) {
                writeAtomically(cfg, kept.joinToString("") { "$it\n" })
                touched += cfg.name
            }
        }
        for ((key, entry) in ledger.all()) {
            val kept = entry.lines.filterNot(::matches).toSet()
            if (kept.size != entry.lines.size) ledger.put(key, entry.copy(lines = kept))
        }
        log("launcher: removed ${top.name} from ES-DE (${media.size} media files, collections: ${touched.joinToString().ifEmpty { "none" }})")
    }
}

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun writeAtomically(file: File, text: String) {
    val dir = file.absoluteFile.parentFile
    if (!dir.isDirectory && !dir.mkdirs()) error("cannot create ${dir.path}")
    val tmp = File(dir, ".${file.name}.tmp")
    tmp.writeText(text, Charsets.UTF_8)
    try {
        moveIntoPlace(tmp, file)
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}

private fun moveIntoPlace(from: File, to: File) {
    try {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
