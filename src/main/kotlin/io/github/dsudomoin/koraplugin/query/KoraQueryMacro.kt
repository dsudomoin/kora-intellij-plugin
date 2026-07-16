package io.github.dsudomoin.koraplugin.query

import com.intellij.openapi.util.TextRange

enum class KoraMacroCommand { TABLE, SELECTS, INSERTS, UPDATES, WHERE, UNKNOWN }

enum class KoraMacroFilterMode { NONE, INCLUDE, EXCLUDE }

data class KoraQueryMacro(
    val target: String,
    val command: KoraMacroCommand,
    val filterMode: KoraMacroFilterMode,
    val fields: List<String>,
    val rawText: String,
    val rangeInHost: TextRange,
)

data class KoraQueryParam(
    val rangeInHost: TextRange,
)
