package ru.dsudomoin.koraplugin.config

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.codeInsight.navigation.openFileWithPsiElement
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
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
import java.awt.event.MouseEvent

class ConfigSourceLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (!isConfigMemberIdentifier(element)) return null

        val uClass = findContainingConfigClass(element) ?: return null
        if (!isInConfigSourceClass(uClass)) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.FileTypes.Config,
            { "Navigate to config key" },
            ConfigGutterNavigationHandler(),
            GutterIconRenderer.Alignment.LEFT,
            { "Navigate to config key" },
        )
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
        // Check if this class is used as a return type (directly, or as List/Set/Map element)
        // by any method in a @ConfigSource class
        return ConfigPathResolver.resolveMemberToConfigPath(
            uClass.javaPsi.methods.firstOrNull()?.name ?: return false,
            uClass.javaPsi
        ) != null
    }

    private class ConfigGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, elt: PsiElement) {
            val configPath = resolveConfigPath(elt) ?: return

            val targets = findConfigKeyElements(elt.project, configPath)
            when (targets.size) {
                0 -> {
                    JBPopupFactory.getInstance()
                        .createHtmlTextBalloonBuilder(
                            "Config key not found: <b>$configPath</b>",
                            MessageType.WARNING,
                            null,
                        )
                        .createBalloon()
                        .show(RelativePoint(e), Balloon.Position.above)
                }
                1 -> openFileWithPsiElement(targets.single(), true, true)
                else -> PsiTargetNavigator(targets).navigate(e, "Choose Config Key", elt.project)
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

fun findConfigKeyElements(project: Project, configPath: String): List<PsiElement> {
    val targets = mutableListOf<PsiElement>()
    val scope = GlobalSearchScope.projectScope(project)
    val psiManager = PsiManager.getInstance(project)

    val configFileExtensions = listOf("yaml", "yml", "conf")
    for (ext in configFileExtensions) {
        val files = FilenameIndex.getAllFilesByExt(project, ext, scope)
        for (vf in files) {
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
    return try {
        findHoconKeysByPathImpl(psiFile, configPath)
    } catch (_: ClassNotFoundException) {
        emptyList()
    } catch (_: NoClassDefFoundError) {
        emptyList()
    }
}

private fun findHoconKeysByPathImpl(psiFile: PsiElement, configPath: String): List<PsiElement> {
    val hKeyClass = Class.forName("org.jetbrains.plugins.hocon.psi.HKey")
    val fullPathTextMethod = hKeyClass.getMethod("fullPathText")
    val patternSegments = configPath.split(".")

    val allKeys = mutableListOf<PsiElement>()
    collectElements(psiFile, hKeyClass, allKeys)

    // Pass 1: exact fullPathText matching
    val results = mutableListOf<PsiElement>()
    for (key in allKeys) {
        val fullPath = hoconKeyFullPath(key, fullPathTextMethod) ?: continue
        if (matchesWildcardPath(fullPath, patternSegments)) {
            results.add(key)
        }
    }
    if (results.isNotEmpty()) return results

    // Pass 2: fallback for keys inside list elements (fullPathText doesn't include list parent path)
    // Try splitting the path: prefix matches a key whose value is a list, suffix matches keys inside elements
    for (splitAt in patternSegments.size - 1 downTo 1) {
        val prefixPath = patternSegments.subList(0, splitAt).joinToString(".")
        val suffixPath = patternSegments.subList(splitAt, patternSegments.size).joinToString(".")

        for (key in allKeys) {
            val fullPath = hoconKeyFullPath(key, fullPathTextMethod) ?: continue
            if (!normalizedEquals(fullPath, prefixPath)) continue

            // Found the prefix key — search its descendants for keys matching the suffix
            val field = key.parent ?: continue
            val descendantKeys = mutableListOf<PsiElement>()
            collectElements(field, hKeyClass, descendantKeys)

            for (dk in descendantKeys) {
                if (dk === key) continue
                val dkPath = hoconKeyFullPath(dk, fullPathTextMethod) ?: continue
                if (normalizedEquals(dkPath, suffixPath)) {
                    results.add(dk)
                }
            }
        }
        if (results.isNotEmpty()) return results
    }

    return results
}

private fun hoconKeyFullPath(key: PsiElement, fullPathTextMethod: java.lang.reflect.Method): String? {
    val optionResult = fullPathTextMethod.invoke(key)
    val isDefinedMethod = optionResult.javaClass.getMethod("isDefined")
    if (isDefinedMethod.invoke(optionResult) != true) return null
    val getMethod = optionResult.javaClass.getMethod("get")
    return getMethod.invoke(optionResult) as? String
}

private fun matchesWildcardPath(fullPath: String, patternSegments: List<String>): Boolean {
    val pathSegments = fullPath.split(".")
    if (pathSegments.size != patternSegments.size) return false
    return pathSegments.zip(patternSegments).all { (actual, pattern) ->
        pattern == "*" || ConfigPathResolver.camelToKebab(actual) == ConfigPathResolver.camelToKebab(pattern)
    }
}

/** Compares two dot-separated paths normalizing camelCase ↔ kebab-case */
private fun normalizedEquals(a: String, b: String): Boolean {
    val aNorm = a.split(".").joinToString(".") { ConfigPathResolver.camelToKebab(it) }
    val bNorm = b.split(".").joinToString(".") { ConfigPathResolver.camelToKebab(it) }
    return aNorm == bNorm
}

private fun collectElements(element: PsiElement, targetClass: Class<*>, result: MutableList<PsiElement>) {
    if (targetClass.isInstance(element)) {
        result.add(element)
    }
    for (child in element.children) {
        collectElements(child, targetClass, result)
    }
}