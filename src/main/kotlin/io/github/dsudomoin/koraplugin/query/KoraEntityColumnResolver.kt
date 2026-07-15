package io.github.dsudomoin.koraplugin.query

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import io.github.dsudomoin.koraplugin.KoraAnnotations

data class KoraColumn(val fieldName: String, val columnName: String, val isId: Boolean)

data class KoraEntityInfo(val tableName: String, val columns: List<KoraColumn>)

object KoraEntityColumnResolver {

    fun resolveEntity(psiClass: PsiClass): KoraEntityInfo? {
        val className = psiClass.name ?: return null
        val tableName = stringAttr(psiClass, KoraAnnotations.TABLE) ?: snakeCase(className)

        val owners: List<Pair<String, PsiModifierListOwner>> =
            if (psiClass.isRecord) {
                psiClass.recordComponents.map { it.name to it }
            } else {
                psiClass.fields.filter { !it.hasModifierProperty(PsiModifier.STATIC) }.map { it.name to it }
            }
        if (owners.isEmpty()) return null

        val columns = owners.map { (name, owner) ->
            val column = stringAttr(owner, KoraAnnotations.COLUMN) ?: snakeCase(name)
            val isId = AnnotationUtil.isAnnotated(owner, KoraAnnotations.ID, 0)
            KoraColumn(name, column, isId)
        }
        return KoraEntityInfo(tableName, columns)
    }

    private fun stringAttr(owner: PsiModifierListOwner, fqn: String): String? {
        val annotation = AnnotationUtil.findAnnotation(owner, fqn) ?: return null
        return AnnotationUtil.getStringAttributeValue(annotation, "value")?.takeIf { it.isNotBlank() }
    }

    fun snakeCase(name: String): String {
        val sb = StringBuilder()
        for ((i, c) in name.withIndex()) {
            if (c.isUpperCase()) {
                if (i > 0) sb.append('_')
                sb.append(c.lowercaseChar())
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }
}
