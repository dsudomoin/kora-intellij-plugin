package io.github.dsudomoin.koraplugin.query

import com.intellij.openapi.util.TextRange

object KoraQueryMacroParser {

    private val MACRO = Regex("""%\{([^}]*)}""")
    private val PARAM = Regex("""(?<![:\w]):([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""")
    private val COMMAND_TAIL = Regex("""^([A-Za-z]+)\s*(-=|=)?\s*(.*)$""")

    fun parseMacros(text: String, offsetInHost: Int): List<KoraQueryMacro> =
        MACRO.findAll(text).map { match ->
            val inner = match.groupValues[1]
            val range = TextRange(offsetInHost + match.range.first, offsetInHost + match.range.last + 1)
            parseInner(inner, match.value, range)
        }.toList()

    fun parseParams(text: String, offsetInHost: Int): List<KoraQueryParam> {
        val macroRanges = MACRO.findAll(text).map { it.range }.toList()
        return PARAM.findAll(text)
            .filter { p -> macroRanges.none { it.contains(p.range.first) } }
            .map { KoraQueryParam(TextRange(offsetInHost + it.range.first, offsetInHost + it.range.last + 1)) }
            .toList()
    }

    private fun parseInner(inner: String, rawText: String, range: TextRange): KoraQueryMacro {
        val hash = inner.indexOf('#')
        if (hash < 0) {
            return KoraQueryMacro(
                inner.trim(),
                KoraMacroCommand.UNKNOWN,
                KoraMacroFilterMode.NONE,
                emptyList(),
                rawText,
                range
            )
        }
        val target = inner.substring(0, hash).trim()
        val rest = inner.substring(hash + 1).trim()
        val tail = COMMAND_TAIL.find(rest)
            ?: return KoraQueryMacro(
                target,
                KoraMacroCommand.UNKNOWN,
                KoraMacroFilterMode.NONE,
                emptyList(),
                rawText,
                range
            )
        val command = when (tail.groupValues[1]) {
            "table" -> KoraMacroCommand.TABLE
            "selects" -> KoraMacroCommand.SELECTS
            "inserts" -> KoraMacroCommand.INSERTS
            "updates" -> KoraMacroCommand.UPDATES
            "where" -> KoraMacroCommand.WHERE
            else -> KoraMacroCommand.UNKNOWN
        }
        val filterMode = when (tail.groupValues[2]) {
            "=" -> KoraMacroFilterMode.INCLUDE
            "-=" -> KoraMacroFilterMode.EXCLUDE
            else -> KoraMacroFilterMode.NONE
        }
        val fields = tail.groupValues[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return KoraQueryMacro(target, command, filterMode, fields, rawText, range)
    }
}
