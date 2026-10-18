package es.davidrg.rommsync.core.metadata

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * One `<game>` of an ES-DE gamelist: [path] as ES-DE writes it (`./<file>`,
 * relative to the system's ROM folder) and the RomM-managed child elements in
 * output order (element name → text). Only non-blank values are written.
 */
data class EsdeGameEntry(val path: String, val fields: Map<String, String>)

/**
 * Text-level upsert of `<ESDE dir>/gamelists/<system>/gamelist.xml`.
 *
 * ES-DE (with `SaveGamelistsMode=always`) and the user also write these files,
 * so everything this code does not manage survives byte for byte: other
 * `<game>`/`<folder>` blocks, comments, and unknown children of a matched game
 * (`<playcount>`, `<lastplayed>`, `<favorite>`, `<sortname>`, …). A matched game
 * gets each managed element replaced in place, or inserted before `</game>`
 * when absent; managed elements without a new value are left as they are.
 * Games match on `<path>` after entity decoding and dropping a leading `./`.
 */
object EsdeGamelist {

    /** Managed elements, in the order new entries are written. */
    val MANAGED_ELEMENTS = listOf("name", "desc", "rating", "releasedate", "developer", "publisher", "genre", "players")

    private val GAME_BLOCK = Regex("""<game(\s[^>]*)?>[\s\S]*?</game>""")
    private val PATH_FIELD = Regex("""<path>([\s\S]*?)</path>""")
    private val CLOSE_ROOT = Regex("""</gameList\s*>""")
    private val EMPTY_ROOT = Regex("""<gameList\s*/>""")
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""")
    private val INVALID_XML_CHARS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]")

    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Upserts [entries] into [file] (created with its folders when absent) and
     * writes it atomically. Throws when the existing file has no recognisable
     * `<gameList>` root, rather than overwriting something it cannot merge.
     */
    fun upsertFile(file: File, entries: List<EsdeGameEntry>) {
        if (entries.isEmpty()) return
        val key = file.absoluteFile.normalize().path
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            val existing = if (file.isFile) file.readText(Charsets.UTF_8) else null
            val merged = upsert(existing, entries)
            if (merged == existing) return
            writeAtomically(file, merged)
        }
    }

    /** Pure merge: returns the new document text for [existing] (null/blank = new file). */
    fun upsert(existing: String?, entries: List<EsdeGameEntry>): String {
        var text = existing?.takeIf { it.isNotBlank() } ?: "<?xml version=\"1.0\"?>\n<gameList>\n</gameList>\n"
        EMPTY_ROOT.find(text)?.let { text = text.replaceRange(it.range, "<gameList>\n</gameList>") }
        if (CLOSE_ROOT.find(text) == null) throw IllegalStateException("not an ES-DE gamelist (no </gameList>)")

        val pending = LinkedHashMap<String, EsdeGameEntry>()
        for (entry in entries) pending[normalizePath(entry.path)] = entry

        // Update matched blocks in place (first match per path wins).
        val updated = StringBuilder()
        var last = 0
        for (block in GAME_BLOCK.findAll(text)) {
            val path = PATH_FIELD.find(block.value)?.groupValues?.get(1)?.let { normalizePath(decode(it.trim())) }
            val entry = path?.let { pending.remove(it) } ?: continue
            updated.append(text, last, block.range.first)
            updated.append(mergeBlock(block.value, entry))
            last = block.range.last + 1
        }
        updated.append(text, last, text.length)
        text = updated.toString()

        if (pending.isEmpty()) return text
        val close = CLOSE_ROOT.findAll(text).last()
        val lineStart = text.lastIndexOf('\n', close.range.first - 1) + 1
        val beforeClose = text.substring(lineStart, close.range.first)
        // Insert on its own line before the closing tag, keeping whatever indented it.
        val insertAt = if (beforeClose.isBlank()) lineStart else close.range.first
        val prefix = if (beforeClose.isBlank()) "" else "\n"
        val blocks = pending.values.joinToString("") { newBlock(it) }
        return text.substring(0, insertAt) + prefix + blocks + text.substring(insertAt)
    }

    private fun mergeBlock(block: String, entry: EsdeGameEntry): String {
        var result = block
        val indent = childIndent(block)
        for ((tag, value) in entry.fields) {
            if (value.isBlank()) continue
            val element = "<$tag>${escape(value)}</$tag>"
            val existing = Regex("""<$tag(\s[^>]*)?>[\s\S]*?</$tag>|<$tag(\s[^>]*)?/>""").find(result)
            result = if (existing != null) {
                result.replaceRange(existing.range, element)
            } else {
                val close = result.lastIndexOf("</game>")
                val lineStart = result.lastIndexOf('\n', close - 1) + 1
                if (result.substring(lineStart, close).isBlank()) {
                    result.substring(0, lineStart) + indent + element + "\n" + result.substring(lineStart)
                } else {
                    result.substring(0, close) + element + result.substring(close)
                }
            }
        }
        return result
    }

    /** Indentation of the block's `<path>` line, ES-DE's two tabs when it cannot tell. */
    private fun childIndent(block: String): String {
        val path = block.indexOf("<path>")
        if (path < 0) return "\t\t"
        val lineStart = block.lastIndexOf('\n', path - 1) + 1
        val indent = block.substring(lineStart, path)
        return if (lineStart > 0 && indent.isBlank()) indent else "\t\t"
    }

    private fun newBlock(entry: EsdeGameEntry): String = buildString {
        append("\t<game>\n")
        append("\t\t<path>").append(escape(entry.path)).append("</path>\n")
        for ((tag, value) in entry.fields) {
            if (value.isBlank()) continue
            append("\t\t<").append(tag).append('>').append(escape(value)).append("</").append(tag).append(">\n")
        }
        append("\t</game>\n")
    }

    internal fun normalizePath(path: String): String = path.trim().removePrefix("./")

    /** XML text escaping; drops characters XML 1.0 cannot carry. */
    fun escape(value: String): String = INVALID_XML_CHARS.replace(value, "")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    internal fun decode(value: String): String = ENTITY.replace(value) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> runCatching {
                val code = if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt()
                String(Character.toChars(code))
            }.getOrDefault(m.value)
        }
    }

    private fun writeAtomically(file: File, text: String) {
        val dir = file.absoluteFile.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("cannot create folder ${dir.path}")
        val tmp = File(dir, ".${file.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
