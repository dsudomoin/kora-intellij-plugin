package ru.dsudomoin.koraplugin.config

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.codeInsight.navigation.openFileWithPsiElement
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.toUElement
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import ru.dsudomoin.koraplugin.KoraAnnotations
import ru.dsudomoin.koraplugin.util.KoraLibraryUtil
import java.awt.event.MouseEvent

class ConfigSourceLineMarkerProvider : LineMarkerProvider, DumbAware {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        val first = elements.firstOrNull() ?: return
        val project = first.project
        if (DumbService.isDumb(project)) return
        if (!KoraLibraryUtil.hasKoraLibrary(project)) return

        for (element in elements) {
            ProgressManager.checkCanceled()
            if (!isConfigMemberIdentifier(element)) continue

            val uClass = findContainingConfigClass(element) ?: continue
            if (!isInConfigSourceClass(uClass)) continue

            result.add(
                LineMarkerInfo(
                    element,
                    element.textRange,
                    AllIcons.FileTypes.Config,
                    { "Navigate to config key" },
                    ConfigGutterNavigationHandler(),
                    GutterIconRenderer.Alignment.LEFT,
                    { "Navigate to config key" },
                )
            )
        }
    }

    private fun isConfigMemberIdentifier(element: PsiElement): Boolean {
        // Java method / Kotlin function (interface config)
        if (element is PsiIdentifier && element.parent is PsiMethod) return true
        if (element.node?.elementType == KtTokens.IDENTIFIER && element.parent is KtNamedFunction) return true
        // Kotlin constructor parameter (data class config)
        if (element.node?.elementType == KtTokens.IDENTIFIER && element.parent is KtParameter) {
            val param = element.parent as KtParameter
            if (param.parent?.parent is KtPrimaryConstructor) return true
        }
        return false
    }

    private fun findContainingConfigClass(element: PsiElement): UClass? {
        val parent = element.parent
        if (parent is KtParameter && parent.parent?.parent is KtPrimaryConstructor) {
            // Constructor parameter → class is the constructor's owner
            return parent.parent?.parent?.parent?.toUElement() as? UClass
        }
        // Method → walk up via UMethod
        val uMethod = element.getUastParentOfType<UMethod>() ?: return null
        return uMethod.getUastParentOfType<UClass>()
    }

    private fun isInConfigSourceClass(uClass: UClass): Boolean {
        return uClass.uAnnotations.any { it.qualifiedName == KoraAnnotations.CONFIG_SOURCE }
                || isNestedInConfigSource(uClass)
                || isReferencedFromConfigSource(uClass)
    }

    private fun isNestedInConfigSource(uClass: UClass): Boolean {
        var parent = uClass.javaPsi.containingClass
        while (parent != null) {
            val parentUClass = parent.toUElement() as? UClass ?: return false
            if (parentUClass.uAnnotations.any { it.qualifiedName == KoraAnnotations.CONFIG_SOURCE }) return true
            parent = parent.containingClass
        }
        return false
    }

    private fun isReferencedFromConfigSource(uClass: UClass): Boolean {
        // Quick check: is this class's FQN used as a return type in any @ConfigSource class?
        val fqn = uClass.qualifiedName ?: return false
        val referencedFqns = ConfigSourceSearch.getReferencedTypeFqns(uClass.javaPsi.project)
        if (fqn !in referencedFqns) return false

        // Confirmed referenced — verify full path resolution
        return ConfigPathResolver.resolveMemberToConfigPath(
            uClass.javaPsi.methods.firstOrNull()?.name ?: return false,
            uClass.javaPsi
        ) != null
    }

    private class ConfigGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, elt: PsiElement) {
            val project = elt.project
            var configPath: String? = null
            var targets: List<PsiElement> = emptyList()

            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    com.intellij.openapi.application.ReadAction.compute<Unit, RuntimeException> {
                        configPath = resolveConfigPath(elt)
                        if (configPath != null) {
                            targets = findConfigKeyElements(project, configPath!!)
                        }
                    }
                },
                "Resolving config key...",
                true,
                project,
            )

            val path = configPath ?: return
            when (targets.size) {
                0 -> {
                    JBPopupFactory.getInstance()
                        .createHtmlTextBalloonBuilder(
                            "Config key not found: <b>$path</b>",
                            MessageType.WARNING,
                            null,
                        )
                        .createBalloon()
                        .show(RelativePoint(e), Balloon.Position.above)
                }
                1 -> openFileWithPsiElement(targets.single(), true, true)
                else -> PsiTargetNavigator(targets).navigate(e, "Choose Config Key", project)
            }
        }

        private fun resolveConfigPath(elt: PsiElement): String? {
            val parent = elt.parent
            // Kotlin constructor parameter (data class property)
            if (parent is KtParameter && parent.parent?.parent is KtPrimaryConstructor) {
                val paramName = parent.name ?: return null
                val uClass = parent.parent?.parent?.parent?.toUElement() as? UClass ?: return null
                return ConfigPathResolver.resolveMemberToConfigPath(paramName, uClass.javaPsi)
            }
            // Method (Java/Kotlin)
            val uMethod = elt.getUastParentOfType<UMethod>() ?: return null
            return ConfigPathResolver.resolveMethodToConfigPath(uMethod.javaPsi)
        }
    }

}

private val CONFIG_FILE_EXTENSIONS = listOf("yaml", "yml", "conf")

fun findConfigKeyElements(project: Project, configPath: String): List<PsiElement> {
    val targets = mutableListOf<PsiElement>()
    val scope = GlobalSearchScope.projectScope(project)
    val psiManager = PsiManager.getInstance(project)

    val configFileExtensions = CONFIG_FILE_EXTENSIONS
    for (ext in configFileExtensions) {
        val files = FilenameIndex.getAllFilesByExt(project, ext, scope)
        for (vf in files) {
            ProgressManager.checkCanceled()
            val psiFile = psiManager.findFile(vf) ?: continue
            when {
                psiFile is YAMLFile -> targets.addAll(findYamlKeysByPath(psiFile, configPath))
                ext == "conf" -> targets.addAll(findHoconKeysByPath(psiFile, configPath))
            }
        }
    }
    return targets
}

private fun findYamlKeysByPath(yamlFile: YAMLFile, path: String): List<PsiElement> {
    val segments = path.split(".")
    val topLevel = yamlFile.documents.firstOrNull()?.topLevelValue as? YAMLMapping
        ?: return emptyList()
    return findYamlKeysInMapping(topLevel, segments, 0)
}

private fun findYamlKeysInMapping(
    mapping: YAMLMapping,
    segments: List<String>,
    index: Int,
): List<PsiElement> {
    if (index >= segments.size) return emptyList()

    if (segments[index] == "*") {
        // Wildcard: recurse into all child mappings
        val results = mutableListOf<PsiElement>()
        for (keyValue in mapping.keyValues) {
            val nested = keyValue.value as? YAMLMapping ?: continue
            results.addAll(findYamlKeysInMapping(nested, segments, index + 1))
        }
        return results
    }

    val segment = segments[index]
    val altSegment = if ('-' in segment) ConfigPathResolver.kebabToCamel(segment)
                     else ConfigPathResolver.camelToKebab(segment)
    val keyValue = mapping.getKeyValueByKey(segment)
        ?: mapping.getKeyValueByKey(altSegment)
        ?: return emptyList()
    if (index == segments.size - 1) return listOfNotNull(keyValue.key ?: keyValue)
    val nested = keyValue.value as? YAMLMapping ?: return emptyList()
    return findYamlKeysInMapping(nested, segments, index + 1)
}

private fun findHoconKeysByPath(psiFile: PsiElement, configPath: String): List<PsiElement> {
    val reflection = HoconReflectionCache.instance ?: return emptyList()
    return findHoconKeysByPathImpl(psiFile, configPath, reflection)
}

private data class HoconReflection(val hKeyClass: Class<*>, val fullPathTextMethod: java.lang.reflect.Method)

private object HoconReflectionCache {
    // Lazy: avoids ExceptionInInitializerError loop when HOCON plugin is not installed.
    // Returns null if HOCON classes are unavailable.
    val instance: HoconReflection? by lazy {
        try {
            val cls = Class.forName("org.jetbrains.plugins.hocon.psi.HKey")
            HoconReflection(cls, cls.getMethod("fullPathText"))
        } catch (_: ClassNotFoundException) { null }
        catch (_: NoClassDefFoundError) { null }
    }
}

private fun findHoconKeysByPathImpl(psiFile: PsiElement, configPath: String, reflection: HoconReflection): List<PsiElement> {
    val hKeyClass = reflection.hKeyClass
    val fullPathTextMethod = reflection.fullPathTextMethod
    val patternSegments = configPath.split(".")

    val allKeys = mutableListOf<PsiElement>()
    collectElements(psiFile, hKeyClass, allKeys)

    // Pass 1: exact fullPathText matching
    val results = mutableListOf<PsiElement>()
    for (key in allKeys) {
        ProgressManager.checkCanceled()
        val fullPath = hoconKeyFullPath(key, fullPathTextMethod) ?: continue
        if (matchesWildcardPath(fullPath, patternSegments)) {
            results.add(key)
        }
    }
    if (results.isNotEmpty()) return results

    // Pass 2: fallback for keys inside list elements (fullPathText doesn't include list parent path)
    // Try splitting the path: prefix matches a key whose value is a list, suffix matches keys inside elements
    for (splitAt in patternSegments.size - 1 downTo 1) {
        // Normalize once per split, not per key
        val prefixNorm = normalizePath(patternSegments.subList(0, splitAt).joinToString("."))
        val suffixNorm = normalizePath(patternSegments.subList(splitAt, patternSegments.size).joinToString("."))

        for (key in allKeys) {
            ProgressManager.checkCanceled()
            val fullPath = hoconKeyFullPath(key, fullPathTextMethod) ?: continue
            if (normalizePath(fullPath) != prefixNorm) continue

            // Found the prefix key — search its descendants for keys matching the suffix
            val field = key.parent ?: continue
            val descendantKeys = mutableListOf<PsiElement>()
            collectElements(field, hKeyClass, descendantKeys)

            for (dk in descendantKeys) {
                if (dk === key) continue
                val dkPath = hoconKeyFullPath(dk, fullPathTextMethod) ?: continue
                if (normalizePath(dkPath) == suffixNorm) {
                    results.add(dk)
                }
            }
        }
        if (results.isNotEmpty()) return results
    }

    return results
}

private data class ScalaOptionMethods(
    val isDefined: java.lang.reflect.Method,
    val get: java.lang.reflect.Method,
)

/** Thread-safe lazy cache for Scala Option reflection methods. */
private object ScalaOptionReflectionCache {
    // ConcurrentHashMap: class → methods. Handles different Option subclasses (Some, None).
    private val cache = java.util.concurrent.ConcurrentHashMap<Class<*>, ScalaOptionMethods>()

    fun get(optionInstance: Any): ScalaOptionMethods {
        return cache.computeIfAbsent(optionInstance.javaClass) { cls ->
            ScalaOptionMethods(cls.getMethod("isDefined"), cls.getMethod("get"))
        }
    }
}

private fun hoconKeyFullPath(key: PsiElement, fullPathTextMethod: java.lang.reflect.Method): String? {
    val optionResult = fullPathTextMethod.invoke(key)
    val methods = ScalaOptionReflectionCache.get(optionResult)
    if (methods.isDefined.invoke(optionResult) != true) return null
    return methods.get.invoke(optionResult) as? String
}

private fun matchesWildcardPath(fullPath: String, patternSegments: List<String>): Boolean {
    val pathSegments = fullPath.split(".")
    if (pathSegments.size != patternSegments.size) return false
    return pathSegments.zip(patternSegments).all { (actual, pattern) ->
        pattern == "*" || ConfigPathResolver.camelToKebab(actual) == ConfigPathResolver.camelToKebab(pattern)
    }
}

/** Normalizes a dot-separated path: camelCase → kebab-case for each segment */
private fun normalizePath(path: String): String =
    path.split(".").joinToString(".") { ConfigPathResolver.camelToKebab(it) }

private fun collectElements(element: PsiElement, targetClass: Class<*>, result: MutableList<PsiElement>) {
    ProgressManager.checkCanceled()
    if (targetClass.isInstance(element)) {
        result.add(element)
    }
    for (child in element.children) {
        collectElements(child, targetClass, result)
    }
}