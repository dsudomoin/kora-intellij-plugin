package ru.dsudomoin.koraplugin.config

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
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
     * Supports both interface methods and data class properties (fields).
     * Handles Map<K, V> return types by skipping one segment (the map key)
     * and continuing resolution with the value type V.
     */
    private fun resolvePathInClass(psiClass: PsiClass, path: String): PsiElement? {
        val segments = path.split(".")
        var currentClass = psiClass
        var lastElement: PsiElement? = null
        var i = 0

        while (i < segments.size) {
            val segment = segments[i]
            val altSegment = if ('-' in segment) kebabToCamel(segment) else camelToKebab(segment)

            // Try method first (interfaces, records), then Kotlin val getter, then field (data classes)
            val method = findMethodOrGetter(currentClass, segment)
                ?: findMethodOrGetter(currentClass, altSegment)
            val returnType: PsiType?
            if (method != null) {
                lastElement = method
                returnType = method.returnType
            } else {
                val field = currentClass.findFieldByName(segment, false)
                    ?: currentClass.findFieldByName(altSegment, false)
                if (field != null) {
                    lastElement = field
                    returnType = field.type
                } else {
                    return lastElement
                }
            }
            i++

            if (returnType == null) return lastElement

            // Check if return type is Map<K, V> — skip one segment (map key), continue with V
            val mapValueClass = resolveMapValueClass(returnType)
            if (mapValueClass != null) {
                i++ // skip the dynamic map key segment
                currentClass = mapValueClass
                continue
            }

            // Check if return type is List<T>/Set<T>/Collection<T> — unwrap to element type T
            val collectionElementClass = resolveCollectionElementClass(returnType)
            if (collectionElementClass != null) {
                currentClass = collectionElementClass
                continue
            }

            val returnClass = PsiUtil.resolveClassInClassTypeOnly(returnType)
            if (returnClass != null) {
                currentClass = returnClass
            } else {
                return lastElement
            }
        }
        return lastElement
    }

    /**
     * Code → Config: resolves a PsiMethod inside a @ConfigSource interface
     * to its full config path (e.g. "hub.spawn.location").
     * Map<K, V> methods produce a "*" wildcard segment in the path.
     */
    fun resolveMethodToConfigPath(method: PsiMethod): String? {
        return resolveMemberToConfigPath(method.name, method.containingClass ?: return null)
    }

    /**
     * Code → Config: resolves a member (method or property) by name within a @ConfigSource class
     * to its full config path (e.g. "hub.spawn.location").
     * Map<K, V> members produce a "*" wildcard segment in the path.
     */
    fun resolveMemberToConfigPath(memberName: String, containingClass: PsiClass): String? {
        val segments = mutableListOf(stripGetterPrefix(memberName))
        var currentClass = containingClass

        // Walk up through enclosing classes until we find one with @ConfigSource
        while (true) {
            val uClass = currentClass.toUElement() as? UClass ?: return null
            val configSourcePath = extractConfigSourcePath(uClass)
            if (configSourcePath != null) {
                segments.add(configSourcePath)
                return segments.reversed().joinToString(".")
            }

            // Check if currentClass is a return type of a member in a parent @ConfigSource class
            val enclosingClass = currentClass.containingClass
            if (enclosingClass != null) {
                // Nested class: find the member in enclosing class that returns this type
                val parent = findMemberReturningType(enclosingClass, currentClass)
                if (parent != null) {
                    if (parent.second != null && resolveMapValueClass(parent.second) != null) {
                        segments.add("*")
                    }
                    segments.add(parent.first)
                }
                currentClass = enclosingClass
            } else {
                // Non-nested: scan all @ConfigSource classes to find one whose member returns this type
                val project = containingClass.project
                val entries = ConfigSourceSearch.findAllConfigSources(project)
                for (entry in entries) {
                    val parent = findMemberReturningType(entry.psiClass, currentClass)
                    if (parent != null) {
                        if (parent.second != null && resolveMapValueClass(parent.second) != null) {
                            segments.add("*")
                        }
                        segments.add(parent.first)
                        segments.add(entry.path)
                        return segments.reversed().joinToString(".")
                    }
                }
                return null
            }
        }
    }

    /** Returns (memberName, memberType) of the method or field in [inClass] that returns [targetClass]. */
    private fun findMemberReturningType(inClass: PsiClass, targetClass: PsiClass): Pair<String, PsiType?>? {
        for (m in inClass.methods) {
            val returnType = m.returnType ?: continue
            val returnClass = PsiUtil.resolveClassInClassTypeOnly(returnType)
            if (returnClass == targetClass
                || resolveMapValueClass(returnType) == targetClass
                || resolveCollectionElementClass(returnType) == targetClass
            ) {
                return stripGetterPrefix(m.name) to returnType
            }
        }
        for (f in inClass.fields) {
            val fieldType = f.type
            val fieldClass = PsiUtil.resolveClassInClassTypeOnly(fieldType)
            if (fieldClass == targetClass || resolveCollectionElementClass(fieldType) == targetClass) {
                return f.name to fieldType
            }
        }
        return null
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

    private val COLLECTION_FQNS = setOf(
        "java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable",
    )

    /**
     * If the type is List<T>, Set<T>, Collection<T> or Iterable<T>, returns the PsiClass of T. Otherwise null.
     */
    private fun resolveCollectionElementClass(type: PsiType?): PsiClass? {
        if (type !is PsiClassType) return null
        val resolved = type.resolve() ?: return null
        if (resolved.qualifiedName !in COLLECTION_FQNS) return null
        val typeArgs = type.parameters
        if (typeArgs.isEmpty()) return null
        return PsiUtil.resolveClassInClassTypeOnly(typeArgs[0])
    }

    private fun extractConfigSourcePath(uClass: UClass): String? {
        val annotation = uClass.uAnnotations.find {
            it.qualifiedName == KoraAnnotations.CONFIG_SOURCE
        } ?: return null
        return annotation.findAttributeValue("value")?.evaluate() as? String
    }

    /** Finds a method by direct name or Kotlin val getter name (get + capitalize). */
    private fun findMethodOrGetter(psiClass: PsiClass, name: String): PsiMethod? {
        psiClass.findMethodsByName(name, true).firstOrNull()?.let { return it }
        val getterName = "get${name.replaceFirstChar { it.uppercase() }}"
        return psiClass.findMethodsByName(getterName, true).firstOrNull()
    }

    /** Strips Kotlin getter prefix: "getSomeProperty" → "someProperty". Returns name as-is if no prefix. */
    private fun stripGetterPrefix(methodName: String): String {
        if (methodName.length > 3 && methodName.startsWith("get") && methodName[3].isUpperCase()) {
            return methodName[3].lowercase() + methodName.substring(4)
        }
        return methodName
    }

    private val CAMEL_REGEX = Regex("([a-z0-9])([A-Z])")
    private val KEBAB_REGEX = Regex("-([a-z])")

    /** camelCase → kebab-case: "hubItems" → "hub-items" */
    fun camelToKebab(name: String): String {
        if (!name.any { it.isUpperCase() }) return name
        return name.replace(CAMEL_REGEX) {
            "${it.groupValues[1]}-${it.groupValues[2].lowercase()}"
        }
    }

    /** kebab-case → camelCase: "hub-items" → "hubItems" */
    fun kebabToCamel(name: String): String {
        if ('-' !in name) return name
        return name.replace(KEBAB_REGEX) {
            it.groupValues[1].uppercase()
        }
    }
}
