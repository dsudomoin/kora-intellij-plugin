package io.github.dsudomoin.koraplugin.query

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.sql.psi.SqlLanguage
import io.github.dsudomoin.koraplugin.util.KoraLibraryUtil
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Injects SQL into Kora `@Query` string literals, replacing each `%{...}` macro and `:param` with a valid
 * SQL placeholder. Placeholder text is added as an injection prefix/suffix with no host range, so the parser
 * sees valid SQL (no false errors) while real SQL fragments keep their highlighting and completion.
 * Registered `order="first"` (see `sql-support.xml`) to take precedence over IntelliJ's own SQL injection.
 */
class KoraQuerySqlInjector : MultiHostInjector {

    override fun elementsToInjectIn(): List<Class<out PsiElement>> =
        listOf(PsiLiteralExpression::class.java, KtStringTemplateExpression::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (context !is PsiLanguageInjectionHost || !context.isValidHost) return
        if (!KoraLibraryUtil.hasKoraLibrary(context.project)) return
        if (context is KtStringTemplateExpression && context.entries.any { it is KtStringTemplateEntryWithExpression }) return
        KoraQuerySupport.getQueryMethod(context) ?: return

        val contentRange = KoraQuerySupport.contentRange(context)
        if (contentRange.isEmpty) return
        val text = contentRange.substring(context.text)

        val regions = buildList {
            KoraQueryMacroParser.parseMacros(text, contentRange.startOffset).forEach {
                add(it.rangeInHost to placeholderFor(it.command))
            }
            KoraQueryMacroParser.parseParams(text, contentRange.startOffset).forEach {
                add(it.rangeInHost to "?")
            }
        }.sortedBy { it.first.startOffset }

        registrar.startInjecting(SqlLanguage.INSTANCE)

        var cursor = contentRange.startOffset
        var prefix: String? = null
        var placed = false
        for ((range, placeholder) in regions) {
            val chunk = TextRange(cursor, range.startOffset)
            if (chunk.isEmpty) {
                prefix = (prefix ?: "") + placeholder
            } else {
                registrar.addPlace(prefix, placeholder, context, chunk)
                prefix = null
                placed = true
            }
            cursor = range.endOffset
        }
        val tail = TextRange(cursor, contentRange.endOffset)
        if (!tail.isEmpty || !placed || prefix != null) {
            registrar.addPlace(prefix, null, context, tail)
        }
        registrar.doneInjecting()
    }

    private fun placeholderFor(command: KoraMacroCommand): String = when (command) {
        KoraMacroCommand.SELECTS -> "_kora_col_"
        KoraMacroCommand.TABLE -> "_kora_tbl_"
        KoraMacroCommand.INSERTS -> "_kora_tbl_(_c_) VALUES(?)"
        KoraMacroCommand.UPDATES -> "_c_ = ?"
        KoraMacroCommand.WHERE -> "_c_ = ?"
        KoraMacroCommand.UNKNOWN -> "_kora_col_"
    }
}
