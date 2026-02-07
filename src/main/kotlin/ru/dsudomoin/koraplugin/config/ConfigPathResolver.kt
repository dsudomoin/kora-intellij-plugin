package ru.dsudomoin.koraplugin.config

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiUtil
import org.jetbrains.uast.UClass
import org.jetbrains.uast.toUElement
import ru.dsudomoin.koraplugin.KoraAnnotations

object ConfigPathResolver {

    /**
     * Config → Code: resolves a full config key path (e.g. "hub.spawn.location")
     * to the corresponding PsiElement (@ConfigSource class or method within it).
     */
    fun resolveConfigKeyToMethod(project: Project, fullPath: String): PsiElement? {
        val entries = ConfigSourceSearch.findAllConfigSources(project)

        // Find the @ConfigSource whose path is a prefix of fullPath
        val entry = entries
            .filter { fullPath == it.path || fullPath.startsWith("${it.path}.") }
            .maxByOrNull { it.path.length }
            ?: return null

        if (fullPath == entry.path) {
            return entry.psiClass
        }

        val remaining = fullPath.removePrefix("${entry.path}.")
        return resolvePathInClass(entry.psiClass, remaining)
    }

    /**
     * Resolves a dot-separated path within a @ConfigSource class hierarchy.
     * Handles Map<K, V> return types by skipping one segment (the map key)
     * and continuing resolution with the value type V.
     */
    private fun resolvePathInClass(psiClass: PsiClass, path: String): PsiMethod? {
        val segments = path.split(".")
        var currentClass = psiClass
        var lastMethod: PsiMethod? = null
        var i = 0

        while (i < segments.size) {
            val method = currentClass.findMethodsByName(segments[i], true).firstOrNull() ?: return lastMethod
            lastMethod = method
            i++

            val returnType = method.returnType ?: return lastMethod

            // Check if return type is Map<K, V> — skip one segment (map key), continue with V
            val mapValueClass = resolveMapValueClass(returnType)
            if (mapValueClass != null) {
                i++ // skip the dynamic map key segment
                currentClass = mapValueClass
                continue
            }

            val returnClass = PsiUtil.resolveClassInClassTypeOnly(returnType)
            if (returnClass != null) {
                currentClass = returnClass
            } else {
                return lastMethod
            }
        }
        return lastMethod
    }

    /**
     * Code → Config: resolves a PsiMethod inside a @ConfigSource interface
     * to its full config path (e.g. "hub.spawn.location").
     * Map<K, V> methods produce a "*" wildcard segment in the path.
     */
    fun resolveMethodToConfigPath(method: PsiMethod): String? {
        val segments = mutableListOf(method.name)
        var currentClass = method.containingClass ?: return null

        // Walk up through enclosing classes until we find one with @ConfigSource
        while (true) {
            val uClass = currentClass.toUElement() as? UClass ?: return null
            val configSourcePath = extractConfigSourcePath(uClass)
            if (configSourcePath != null) {
                segments.add(configSourcePath)
                return segments.reversed().joinToString(".")
            }

            // Check if currentClass is a return type of a method in a parent @ConfigSource class
            val enclosingClass = currentClass.containingClass
            if (enclosingClass != null) {
                // Nested interface: find the method in enclosing class that returns this type
                val parentMethod = findMethodReturningType(enclosingClass, currentClass)
                if (parentMethod != null) {
                    if (resolveMapValueClass(parentMethod.returnType) != null) {
                        segments.add("*")
                    }
                    segments.add(parentMethod.name)
                }
                currentClass = enclosingClass
            } else {
                // Non-nested: scan all @ConfigSource classes to find one whose method returns this type
                val project = method.project
                val entries = ConfigSourceSearch.findAllConfigSources(project)
                for (entry in entries) {
                    val parentMethod = findMethodReturningType(entry.psiClass, currentClass)
                    if (parentMethod != null) {
                        if (resolveMapValueClass(parentMethod.returnType) != null) {
                            segments.add("*")
                        }
                        segments.add(parentMethod.name)
                        segments.add(entry.path)
                        return segments.reversed().joinToString(".")
                    }
                }
                return null
            }
        }
    }

    private fun findMethodReturningType(inClass: PsiClass, targetClass: PsiClass): PsiMethod? {
        return inClass.methods.find { m ->
            val returnType = m.returnType ?: return@find false
            val returnClass = PsiUtil.resolveClassInClassTypeOnly(returnType)
            if (returnClass == targetClass) return@find true
            // Also check Map<K, V> where V == targetClass
            resolveMapValueClass(returnType) == targetClass
        }
    }

    /**
     * If the type is Map<K, V>, returns the PsiClass of V. Otherwise null.
     */
    private fun resolveMapValueClass(type: PsiType?): PsiClass? {
        if (type !is PsiClassType) return null
        val resolved = type.resolve() ?: return null
        if (resolved.qualifiedName != "java.util.Map") return null
        val typeArgs = type.parameters
        if (typeArgs.size < 2) return null
        return PsiUtil.resolveClassInClassTypeOnly(typeArgs[1])
    }

    private fun extractConfigSourcePath(uClass: UClass): String? {
        val annotation = uClass.uAnnotations.find {
            it.qualifiedName == KoraAnnotations.CONFIG_SOURCE
        } ?: return null
        return annotation.findAttributeValue("value")?.evaluate() as? String
    }
}
