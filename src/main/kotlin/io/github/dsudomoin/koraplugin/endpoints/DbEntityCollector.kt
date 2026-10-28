package io.github.dsudomoin.koraplugin.endpoints

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import io.github.dsudomoin.koraplugin.KoraAnnotations
import org.jetbrains.uast.UClass
import org.jetbrains.uast.toUElement

/**
 * Database entities: classes/records annotated with @Table (Kora database-common) and/or
 * @EntityJdbc (database-jdbc; Kora's JDBC generator does not require @Table). The display
 * name comes from the @Table `value` attribute and falls back to the class name when
 * @Table is absent or has an empty value.
 */
object DbEntityCollector {

    fun collect(project: Project): List<DbEntity> {
        val facade = JavaPsiFacade.getInstance(project)
        val allScope = GlobalSearchScope.allScope(project)
        val annotationClasses = listOf(KoraAnnotations.TABLE, KoraAnnotations.ENTITY_JDBC)
            .mapNotNull { fqn -> facade.findClass(fqn, allScope) }
        if (annotationClasses.isEmpty()) return emptyList()

        val projectScope = GlobalSearchScope.projectScope(project)
        val seen = HashSet<String>()
        val entities = mutableListOf<DbEntity>()
        annotationClasses.forEach { annotationClass ->
            AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope).forEach { psiClass ->
                ProgressManager.checkCanceled()
                val uClass = psiClass.toUElement() as? UClass ?: return@forEach
                val classFqn = psiClass.qualifiedName ?: return@forEach
                if (!seen.add(classFqn)) return@forEach
                val tableName = uClass.findAnnotation(KoraAnnotations.TABLE)
                    ?.let { AttributeValueResolver.resolveStringAttributeValue(it, "value") }
                    ?.takeIf { it.isNotEmpty() }
                    ?: psiClass.name
                    ?: return@forEach

                entities.add(DbEntity(tableName, classFqn, EndpointTarget(classFqn, null, null, 0)))
            }
        }
        return entities.sortedWith(compareBy({ it.tableName }, { it.classFqn }))
    }
}
