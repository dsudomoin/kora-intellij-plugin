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
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.toUElement
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import ru.dsudomoin.koraplugin.KoraAnnotations
import java.awt.event.MouseEvent

class ConfigSourceLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (!isMethodIdentifier(element)) return null

        val uMethod = element.getUastParentOfType<UMethod>() ?: return null
        val uClass = uMethod.getUastParentOfType<UClass>() ?: return null
        if (!isInConfigSourceInterface(uClass)) return null

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

    private fun isMethodIdentifier(element: PsiElement): Boolean {
        if (element is PsiIdentifier && element.parent is PsiMethod) return true
        if (element.node?.elementType == KtTokens.IDENTIFIER && element.parent is KtNamedFunction) return true
        return false
    }

    private fun isInConfigSourceInterface(uClass: UClass): Boolean {
        return uClass.uAnnotations.any { it.qualifiedName == KoraAnnotations.CONFIG_SOURCE }
                || isNestedInConfigSource(uClass)
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

    private class ConfigGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, elt: PsiElement) {
            val uMethod = elt.getUastParentOfType<UMethod>() ?: return
            val psiMethod = uMethod.javaPsi
            val configPath = ConfigPathResolver.resolveMethodToConfigPath(psiMethod) ?: return

            val targets = findConfigKeyElements(elt.project, configPath)
            when (targets.size) {
                0 -> return
                1 -> openFileWithPsiElement(targets.single(), true, true)
                else -> PsiTargetNavigator(targets).navigate(e, "Choose Config Key", elt.project)
            }
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

    val keyValue = mapping.getKeyValueByKey(segments[index]) ?: return emptyList()
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

    val results = mutableListOf<PsiElement>()
    for (key in allKeys) {
        val optionResult = fullPathTextMethod.invoke(key)
        val isDefinedMethod = optionResult.javaClass.getMethod("isDefined")
        if (isDefinedMethod.invoke(optionResult) != true) continue
        val getMethod = optionResult.javaClass.getMethod("get")
        val fullPath = getMethod.invoke(optionResult) as? String ?: continue

        if (matchesWildcardPath(fullPath, patternSegments)) {
            results.add(key)
        }
    }
    return results
}

private fun matchesWildcardPath(fullPath: String, patternSegments: List<String>): Boolean {
    val pathSegments = fullPath.split(".")
    if (pathSegments.size != patternSegments.size) return false
    return pathSegments.zip(patternSegments).all { (actual, pattern) ->
        pattern == "*" || actual == pattern
    }
}

private fun collectElements(element: PsiElement, targetClass: Class<*>, result: MutableList<PsiElement>) {
    if (targetClass.isInstance(element)) {
        result.add(element)
    }
    for (child in element.children) {
        collectElements(child, targetClass, result)
    }
}