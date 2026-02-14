package ru.dsudomoin.koraplugin.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YamlPsiElementVisitor
import ru.dsudomoin.koraplugin.config.ConfigPathResolver
import ru.dsudomoin.koraplugin.config.ConfigSourceSearch
import ru.dsudomoin.koraplugin.config.KoraConfigAnnotationRegistry
import ru.dsudomoin.koraplugin.config.yaml.buildYamlKeyPath
import ru.dsudomoin.koraplugin.util.KoraLibraryUtil

class KoraUnknownConfigKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!KoraLibraryUtil.hasKoraLibrary(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        return object : YamlPsiElementVisitor() {
            override fun visitKeyValue(keyValue: YAMLKeyValue) {
                checkKeyValue(keyValue, holder)
            }
        }
    }

    private fun checkKeyValue(keyValue: YAMLKeyValue, holder: ProblemsHolder) {
        val fullPath = buildYamlKeyPath(keyValue) ?: return
        val project = holder.project

        // Skip well-known Kora framework prefixes (may not have @ConfigSource)
        if (KNOWN_KORA_PREFIXES.any { prefix ->
            fullPath == prefix ||
                fullPath.length > prefix.length && fullPath[prefix.length] == '.' && fullPath.startsWith(prefix)
        }) return

        // Check if path matches any @ConfigSource (zero-alloc comparisons)
        val configSources = ConfigSourceSearch.findAllConfigSources(project)
        if (configSources.any { entry ->
            val ep = entry.path
            fullPath == ep ||
                fullPath.length > ep.length && fullPath[ep.length] == '.' && fullPath.startsWith(ep) ||
                ep.length > fullPath.length && ep[fullPath.length] == '.' && ep.startsWith(fullPath)
        }) return

        // Check if path matches any annotation-mapped config
        for (fqn in KoraConfigAnnotationRegistry.allAnnotationFqns) {
            val m = KoraConfigAnnotationRegistry.findMappingForAnnotation(fqn) ?: continue
            if (m.configPathPrefix != null && (fullPath.startsWith(m.configPathPrefix) || m.configPathPrefix.startsWith(fullPath))) return
        }

        // Check via ConfigPathResolver (resolves into class members)
        if (ConfigPathResolver.resolveConfigKeyToMethod(project, fullPath) != null) return

        // Check annotation-based reverse lookup
        if (KoraConfigAnnotationRegistry.findAnnotatedElements(project, fullPath).isNotEmpty()) return

        val key = keyValue.key ?: return
        holder.registerProblem(
            key,
            "Unknown Kora config key: '$fullPath'",
            ProblemHighlightType.WEAK_WARNING,
        )
    }

    companion object {
        private val KNOWN_KORA_PREFIXES = setOf(
            "httpServer",
            "grpcServer",
            "grpcClient",
            "database",
            "kafka",
            "logging",
            "tracing",
            "opentelemetry",
            "resilient",
            "cache",
            "scheduling",
            "httpClient",
            "soap",
            "s3client",
        )
    }
}
