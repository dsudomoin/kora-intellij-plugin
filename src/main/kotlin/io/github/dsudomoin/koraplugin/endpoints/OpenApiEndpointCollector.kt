package io.github.dsudomoin.koraplugin.endpoints

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import io.github.dsudomoin.koraplugin.KoraAnnotations
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Strategy B: endpoints declared via the Kora OpenAPI generator. Paths and verbs are parsed
 * from OpenAPI specs (yaml, yml or json files under an `openapi` directory, by convention
 * src/main/resources/openapi); spec paths are prefixed with the generator task's `prefixPath`
 * so they match the generated routes. The delegate method name equals the spec operationId —
 * user implementations are @Component classes implementing a generated `*Delegate` interface.
 */
object OpenApiEndpointCollector {

    private val SPEC_EXTENSIONS = listOf("yaml", "yml", "json")
    private val OPENAPI_VERBS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

    /** `prefixPath.set("/api")` or `prefixPath = "/api"` in a Gradle build script. */
    private val PREFIX_PATH_PATTERN = Regex("""prefixPath\s*(?:\.\s*set\s*\(\s*|=\s*)"([^"]*)"""")

    fun collect(project: Project): List<KoraEndpoint> {
        val specs = locateSpecFiles(project)
        if (specs.isEmpty()) return emptyList()
        val delegateMethods = findDelegateMethodTargets(project)
        ProgressManager.checkCanceled()
        val prefixPath = findGeneratorPrefixPath(project)
        return specs.flatMap { file ->
            ProgressManager.checkCanceled()
            parseSpec(project, file).map { endpoint ->
                val implTargets = endpoint.operationId?.let { delegateMethods[it].orEmpty() } ?: emptyList()
                // Deterministic target order: delegate implementations first, spec location last.
                endpoint.copy(
                    path = prefixPath + endpoint.path,
                    targets = implTargets + endpoint.targets,
                )
            }
        }
    }

    /** Spec files live under an `openapi` directory by Kora convention (src/main/resources/openapi). */
    fun locateSpecFiles(project: Project): List<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)
        return SPEC_EXTENSIONS
            .flatMap { ext -> FilenameIndex.getAllFilesByExt(project, ext, scope) }
            .filter { it.path.contains("/openapi/") }
            .distinct()
            .sortedBy { it.path }
    }

    /**
     * The OpenAPI generator task's `prefixPath` from a Gradle build script
     * (`prefixPath.set("/api")` or `prefixPath = "/api"`), "" when not set. Text-based
     * on purpose: the task is configured in the user's build script, not in indexed PSI.
     */
    private fun findGeneratorPrefixPath(project: Project): String {
        val scope = GlobalSearchScope.projectScope(project)
        val scripts = listOf("gradle", "kts")
            .flatMap { ext -> FilenameIndex.getAllFilesByExt(project, ext, scope) }
            .filter { it.name.startsWith("build.gradle") }
            .distinct()
            .sortedBy { it.path }
        for (script in scripts) {
            val prefix = PREFIX_PATH_PATTERN.find(VfsUtil.loadText(script))
                ?.groupValues?.get(1)
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            return prefix
        }
        return ""
    }

    /** Pure PSI parsing of a single spec file — no index access, safe to unit-test. */
    fun parseSpec(project: Project, file: VirtualFile): List<KoraEndpoint> {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        return when (psiFile) {
            is YAMLFile -> parseYamlSpec(psiFile, file)
            is JsonFile -> parseJsonSpec(psiFile, file)
            else -> emptyList()
        }
    }

    private fun parseYamlSpec(file: YAMLFile, virtualFile: VirtualFile): List<KoraEndpoint> {
        val endpoints = mutableListOf<KoraEndpoint>()
        for (document in file.documents) {
            val root = document.topLevelValue as? YAMLMapping ?: continue
            val paths = root.getKeyValueByKey("paths")?.value as? YAMLMapping ?: continue
            for (pathKeyValue in paths.keyValues) {
                val path = pathKeyValue.keyText.takeIf { it.isNotEmpty() } ?: continue
                val pathMapping = pathKeyValue.value as? YAMLMapping ?: continue
                val offset = pathKeyValue.key?.textOffset ?: pathKeyValue.textOffset
                for (operationKeyValue in pathMapping.keyValues) {
                    if (operationKeyValue.keyText.lowercase() !in OPENAPI_VERBS) continue
                    val operationMapping = operationKeyValue.value as? YAMLMapping ?: continue
                    endpoints.add(
                        KoraEndpoint(
                            verb = normalizeVerb(operationKeyValue.keyText),
                            path = path,
                            operationId = operationMapping.getKeyValueByKey("operationId")?.valueText?.takeIf { it.isNotEmpty() },
                            summary = operationMapping.getKeyValueByKey("summary")?.valueText?.takeIf { it.isNotEmpty() },
                            sources = setOf(EndpointSource.OPENAPI),
                            targets = listOf(EndpointTarget.spec(virtualFile.url, offset)),
                        ),
                    )
                }
            }
        }
        return endpoints
    }

    private fun parseJsonSpec(file: JsonFile, virtualFile: VirtualFile): List<KoraEndpoint> {
        val root = file.topLevelValue as? JsonObject ?: return emptyList()
        val paths = root.findProperty("paths")?.value as? JsonObject ?: return emptyList()
        val endpoints = mutableListOf<KoraEndpoint>()
        for (pathProperty in paths.propertyList) {
            val path = pathProperty.name.takeIf { it.isNotEmpty() } ?: continue
            val pathObject = pathProperty.value as? JsonObject ?: continue
            val offset = pathProperty.nameElement.textOffset
            for (operationProperty in pathObject.propertyList) {
                val operationName = operationProperty.name
                if (operationName.lowercase() !in OPENAPI_VERBS) continue
                val operationObject = operationProperty.value as? JsonObject ?: continue
                endpoints.add(
                    KoraEndpoint(
                        verb = normalizeVerb(operationName),
                        path = path,
                        operationId = operationObject.findProperty("operationId")?.value?.stringValue(),
                        summary = operationObject.findProperty("summary")?.value?.stringValue(),
                        sources = setOf(EndpointSource.OPENAPI),
                        targets = listOf(EndpointTarget.spec(virtualFile.url, offset)),
                    ),
                )
            }
        }
        return endpoints
    }

    /**
     * @Component classes implementing a generated `*Delegate` interface, keyed by method name
     * (delegate method names equal spec operationIds). Matching is name-based on purpose:
     * delegate interfaces are generated code that may not always be indexed.
     */
    private fun findDelegateMethodTargets(project: Project): Map<String, List<EndpointTarget>> {
        val componentClass = JavaPsiFacade.getInstance(project)
            .findClass(KoraAnnotations.COMPONENT, GlobalSearchScope.allScope(project))
            ?: return emptyMap()

        val result = HashMap<String, MutableList<EndpointTarget>>()
        AnnotatedElementsSearch.searchPsiClasses(componentClass, GlobalSearchScope.projectScope(project)).forEach { psiClass ->
            ProgressManager.checkCanceled()
            // implementsListTypes + className: the reference name, no resolve, so unindexed delegates still match.
            if (psiClass.implementsListTypes.none { it.className?.endsWith("Delegate") == true }) return@forEach
            val classFqn = psiClass.qualifiedName ?: return@forEach
            for (method in psiClass.methods) {
                result.getOrPut(method.name) { mutableListOf() }.add(EndpointTarget.method(classFqn, method.name))
            }
        }
        return result
    }

    private fun JsonValue?.stringValue(): String? = (this as? JsonStringLiteral)?.value?.takeIf { it.isNotEmpty() }
}
