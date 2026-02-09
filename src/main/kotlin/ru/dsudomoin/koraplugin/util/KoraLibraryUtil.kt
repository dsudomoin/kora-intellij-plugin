package ru.dsudomoin.koraplugin.util

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import ru.dsudomoin.koraplugin.KoraAnnotations

object KoraLibraryUtil {

    fun hasKoraLibrary(project: Project): Boolean {
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val facade = JavaPsiFacade.getInstance(project)
            val scope = GlobalSearchScope.allScope(project)
            val found = facade.findClass(KoraAnnotations.COMPONENT, scope) != null
            CachedValueProvider.Result.create(found, ProjectRootModificationTracker.getInstance(project))
        }
    }
}
