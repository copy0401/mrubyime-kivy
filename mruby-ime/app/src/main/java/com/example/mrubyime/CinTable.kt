package com.example.mrubyime

/**
 * Lightweight `.cin` header parser used by the settings screen and the
 * dictionary repository. The authoritative parser lives in
 * `assets/mrubyime_engine.rb` (mruby); this one only validates an imported
 * file and shows its metadata without touching the live engine.
 */
data class CinInfo(
    val name: String,
    val ename: String,
    val selkeys: String,
    /** Legal key characters, concatenated. */
    val keys: String,
    /** `%keyname` entries: physical key -> key-cap label. */
    val keynames: Map<String, String>,
    val maxCode: Int,
    val codes: Int,
    val entries: Int
)

object CinTable {
    /** Same order of magnitude as the 220 KB built-in table; 4 MB is generous. */
    const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * Returns null when [bytes] is not a usable `.cin` table. Accepts any
     * single-character key set, so 注音/倉頡/行列/大易/五筆… all validate.
     */
    fun parse(bytes: ByteArray): CinInfo? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")

        var section = 0 // 0 = none, 1 = %keyname, 2 = %chardef
        var name = ""
        var ename = ""
        var selkeys = "0123456789"
        val keynames = LinkedHashMap<String, String>()
        val keys = LinkedHashSet<Char>()
        val codeSet = HashSet<String>()
        var maxCode = 0
        var entries = 0

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) continue

            if (line.startsWith("%")) {
                when {
                    line.startsWith("%selkey") -> {
                        val p = splitKeyValue(line)
                        // Lowercased to match the engine: key caps are lowercase.
                        if (p != null && p.second.isNotEmpty()) selkeys = p.second.lowercase()
                    }
                    line.startsWith("%cname") -> {
                        splitKeyValue(line)?.let { name = it.second }
                    }
                    line.startsWith("%ename") -> {
                        splitKeyValue(line)?.let { ename = it.second }
                    }
                    line.startsWith("%keyname begin") -> section = 1
                    line.startsWith("%chardef begin") -> section = 2
                    line.startsWith("%keyname end") ||
                        line.startsWith("%chardef end") -> section = 0
                }
                continue
            }

            when (section) {
                1 -> {
                    val p = splitKeyValue(line) ?: continue
                    if (p.first.length == 1) {
                        val k = p.first[0].lowercaseChar()
                        keynames[k.toString()] = p.second
                        keys += k
                    }
                }
                2 -> {
                    val p = splitKeyValue(line) ?: continue
                    val code = p.first.lowercase()
                    if (code.isEmpty()) continue
                    codeSet += code
                    entries++
                    if (code.length > maxCode) maxCode = code.length
                    for (c in code) keys += c
                }
            }
        }

        if (codeSet.isEmpty()) return null
        val display = name.ifEmpty { ename.ifEmpty { "自訂碼表" } }
        return CinInfo(
            name = display,
            ename = ename,
            selkeys = selkeys,
            keys = keys.joinToString(""),
            keynames = keynames,
            maxCode = maxCode,
            codes = codeSet.size,
            entries = entries
        )
    }

    /** Splits `%directive value` / `code candidate` on the first whitespace run. */
    private fun splitKeyValue(line: String): Pair<String, String>? {
        var i = 0
        while (i < line.length && !line[i].isWhitespace()) i++
        if (i == 0 || i >= line.length) return null
        var j = i
        while (j < line.length && line[j].isWhitespace()) j++
        if (j >= line.length) return null
        return line.substring(0, i) to line.substring(j)
    }
}
