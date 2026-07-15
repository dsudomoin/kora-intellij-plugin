package io.github.dsudomoin.koraplugin.query

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType

object KoraQueryMacroExpander {

    private val WRAPPERS = setOf(
        "java.util.List", "java.util.Collection", "java.util.Set", "java.util.Optional",
    )
    private const val UPDATE_COUNT = "ru.tinkoff.kora.database.common.UpdateCount"
    private val WRAPPER_SHORT_NAMES = setOf("List", "Collection", "Set", "Optional")

    fun expand(macro: KoraQueryMacro, method: PsiMethod): String? {
        val cls = resolveTargetClass(method, macro.target) ?: return null
        val info = KoraEntityColumnResolver.resolveEntity(cls) ?: return null
        val cols = filterColumns(info, macro)
        return when (macro.command) {
            KoraMacroCommand.TABLE -> info.tableName
            KoraMacroCommand.SELECTS -> cols.joinToString(", ") { it.columnName }
            KoraMacroCommand.INSERTS -> {
                val colList = cols.joinToString(", ") { it.columnName }
                val valList = cols.joinToString(", ") { ":${macro.target}.${it.fieldName}" }
                "${info.tableName}($colList) VALUES ($valList)"
            }

            KoraMacroCommand.UPDATES -> cols.joinToString(", ") { "${it.columnName} = :${macro.target}.${it.fieldName}" }
            KoraMacroCommand.WHERE -> cols.joinToString(" AND ") { "${it.columnName} = :${macro.target}.${it.fieldName}" }
            KoraMacroCommand.UNKNOWN -> null
        }
    }

    fun describe(macro: KoraQueryMacro): String = when (macro.command) {
        KoraMacroCommand.TABLE -> "Имя таблицы сущности (@Table или snake_case класса)"
        KoraMacroCommand.SELECTS -> "Список колонок сущности для SELECT"
        KoraMacroCommand.INSERTS -> "table(колонки) VALUES(:параметры) для INSERT"
        KoraMacroCommand.UPDATES -> "колонка = :значение, … для SET"
        KoraMacroCommand.WHERE -> "условие WHERE по полям сущности"
        KoraMacroCommand.UNKNOWN -> "Макрос Kora @Query"
    }

    fun resolveTargetClass(method: PsiMethod, target: String): PsiClass? {
        val type: PsiType? = if (target == "return") {
            method.returnType
        } else {
            method.parameterList.parameters.firstOrNull { it.name == target }?.type
        }
        return unwrapToClass(type)
    }

    private fun unwrapToClass(type: PsiType?): PsiClass? {
        val classType = type as? PsiClassType ?: return null
        val resolved = classType.resolve()
        val fqn = resolved?.qualifiedName
        if (fqn == UPDATE_COUNT) return null
        // Match wrappers by FQN; fall back to short name when the type is unresolved
        // (a minimal test mock JDK does not resolve java.util.List).
        val isWrapper = fqn in WRAPPERS || (fqn == null && classType.className in WRAPPER_SHORT_NAMES)
        if (isWrapper) return unwrapToClass(elementTypeOf(classType, resolved))
        return resolved
    }

    private fun elementTypeOf(classType: PsiClassType, resolved: PsiClass?): PsiType? {
        classType.parameters.firstOrNull()?.let { return it }
        val typeParam = resolved?.typeParameters?.firstOrNull() ?: return null
        return classType.resolveGenerics().substitutor.substitute(typeParam)
    }

    private fun filterColumns(info: KoraEntityInfo, macro: KoraQueryMacro): List<KoraColumn> {
        val explicit = macro.fields.flatMap { field ->
            if (field == "@id") info.columns.filter { it.isId }.map { it.fieldName } else listOf(field)
        }
        return when (macro.filterMode) {
            KoraMacroFilterMode.NONE -> info.columns
            KoraMacroFilterMode.INCLUDE -> info.columns.filter { it.fieldName in explicit }
            KoraMacroFilterMode.EXCLUDE -> info.columns.filter { it.fieldName !in explicit }
        }
    }
}
