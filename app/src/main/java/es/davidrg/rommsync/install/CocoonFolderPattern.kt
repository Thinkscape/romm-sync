package es.davidrg.rommsync.install

import java.io.File

/**
 * Builds the regex of a Cocoon "Custom" smart folder that holds exactly a
 * RomM collection's installed members.
 *
 * Cocoon 3.07 (`rip.moth.cocoonshell.data.store.l0.c`, SmartFolderType.CUSTOM)
 * compiles the folder's query as a Kotlin `Regex` and lists every game whose
 * display name or file name contains a match. The pattern is therefore an
 * anchored alternation of the members' file names, so neither a partial name
 * nor a display name can match by accident. Pure JVM; unit-tested.
 */
object CocoonFolderPattern {

    /** Matches nothing: an empty collection still owns its folder. */
    const val MATCH_NOTHING = "^(?!)$"

    private const val META = "\\^$.|?*+()[]{}"

    /**
     * Names Cocoon may record as a member's file name: the top-level rom file,
     * or for a game folder the folder itself plus the files directly inside it
     * (Cocoon lists a `<name>.m3u/` folder as one game named after its playlist).
     */
    fun namesFor(files: List<File>): Set<String> {
        val names = sortedSetOf<String>()
        for (file in files) {
            names += file.name
            if (file.isDirectory) {
                file.listFiles()?.filter { it.isFile }?.forEach { names += it.name }
            }
        }
        return names
    }

    /** `^(?:a|b)$` over [names], sorted for a stable result; [MATCH_NOTHING] when empty. */
    fun build(names: Collection<String>): String {
        val distinct = names.filter { it.isNotEmpty() }.toSortedSet()
        if (distinct.isEmpty()) return MATCH_NOTHING
        return distinct.joinToString(separator = "|", prefix = "^(?:", postfix = ")$") { escape(it) }
    }

    fun escape(literal: String): String = buildString(literal.length + 8) {
        for (c in literal) {
            if (c in META) append('\\')
            append(c)
        }
    }
}
