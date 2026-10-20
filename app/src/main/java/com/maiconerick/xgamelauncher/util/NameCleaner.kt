package com.maiconerick.xgamelauncher.util

/**
 * Turns ROM file names into game names: "Ookami (USA).iso.gz" → "Ookami",
 * "Legend of Zelda, The - A Link to the Past (USA) (Rev 1).sfc" →
 * "The Legend of Zelda - A Link to the Past". Disc numbers are kept so multi-disc games
 * stay distinguishable ("Xenosaga Episode II (Disc 1)"). Original casing is kept.
 */
object NameCleaner {

    /** Extensions stripped from the end (repeatedly, so ".iso.gz" / ".bin.7z" go too). */
    private val EXTENSIONS = setOf(
        "iso", "gz", "7z", "zip", "rar", "chd", "cso", "zso", "bin", "cue", "img", "mdf", "mds", "ccd", "sub",
        "pbp", "ecm", "nrg", "gdi", "cdi", "rvz", "wbfs", "wia", "gcz", "gcm", "ciso", "nsp", "xci", "nsz", "xcz",
        "3ds", "cia", "cci", "nds", "dsi", "gba", "gbc", "gb", "sfc", "smc", "fig", "nes", "fds", "unf", "n64", "z64",
        "v64", "md", "smd", "gen", "32x", "sms", "gg", "pce", "sgx", "ngp", "ngc", "ws", "wsc", "vb", "lnx", "a26",
        "a78", "j64", "jag", "col", "int", "vec", "psv", "vpk", "elf", "m3u", "dsk", "adf", "d64", "t64", "tap",
        "tzx", "rom", "dat", "pkg", "apk", "exe", "lha", "xz", "bz2", "tar",
    )
    private val tag = Regex("""\s*[\(\[]([^\)\]]{0,80})[\)\]]""")
    private val disc = Regex("""^(disc|disk|cd)\s*\d+.*$""", RegexOption.IGNORE_CASE)

    /** Clean game name from a file name (with its extension(s)). */
    fun sanitize(fileName: String): String {
        var s = fileName.trim()
        repeat(3) {
            val dot = s.lastIndexOf('.')
            if (dot > 0 && s.substring(dot + 1).lowercase() in EXTENSIONS) s = s.substring(0, dot)
        }
        s = s.replace('_', ' ')
        // Region / revision / language / dump tags go; disc numbers stay.
        val discs = tag.findAll(s).map { it.groupValues[1].trim() }.filter { disc.matches(it) }.toList()
        s = tag.replace(s, "")
        s = s.replace(Regex("""\s{2,}"""), " ").trim().trimEnd('-', ' ', '.', ',')
        // "Legend of Zelda, The - …" / "Legend of Zelda, The" → "The Legend of Zelda …"
        Regex("""^(.+?), (The|A|An)(\s*-\s*.*|$)""").find(s)?.let { m ->
            s = "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}"
        }
        if (discs.isNotEmpty()) s += " (" + discs.joinToString(", ") + ")"
        return s.ifBlank { fileName }
    }

    /**
     * The original cleaner (dots/underscores → spaces, tags dropped, words capitalised).
     * Still used for Windows game names: their play history is recorded under these names.
     */
    fun prettify(raw: String): String {
        var s = raw.replace('_', ' ').replace('.', ' ')
        s = Regex("""\s*[\(\[][^\)\]]{0,80}[\)\]]""").replace(s, "")
        s = Regex("""\s{2,}""").replace(s, " ").trim()
        return s.split(' ').joinToString(" ") { w ->
            if (w.length <= 3 && w.uppercase() == w) w else w.replaceFirstChar { it.uppercase() }
        }
    }

    private val discTag = Regex("""\s*[\(\[]\s*(disc|disk|cd)\s*\d+[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE)

    /** Name to search art with: no disc numbers ("Xenosaga II (Disc 1)" → "Xenosaga II"). */
    fun forArtSearch(name: String): String = discTag.replace(name, "").trim()

    /** The disc suffix of a name (" (Disc 1)"), to keep it when renaming from a search. */
    fun discSuffix(name: String): String = discTag.find(name)?.value?.let { " " + it.trim() } ?: ""

    /** Undo sqlite escaping ("Pokémon" -> "Pokémon", "It''s" -> "It's"). */
    fun unescape(s: String): String {
        return s.replace("''", "'")
    }
}
