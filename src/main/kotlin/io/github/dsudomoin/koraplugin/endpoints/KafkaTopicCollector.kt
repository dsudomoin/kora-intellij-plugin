package io.github.dsudomoin.koraplugin.endpoints

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import io.github.dsudomoin.koraplugin.KoraAnnotations
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElement

/**
 * Kafka consumers: @KafkaListener, keyed by the config path in its `value` attribute.
 * Current Kora annotates methods; older versions annotated classes — both are scanned.
 */
object KafkaTopicCollector {

    fun collect(project: Project): List<KafkaConsumer> {
        val annotationClass = JavaPsiFacade.getInstance(project)
            .findClass(KoraAnnotations.KAFKA_LISTENER, GlobalSearchScope.allScope(project))
            ?: return emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        val byConfigPath = LinkedHashMap<String, MutableList<EndpointTarget>>()

        AnnotatedElementsSearch.searchPsiMethods(annotationClass, scope).forEach { method ->
            ProgressManager.checkCanceled()
            val uMethod = method.toUElement() as? UMethod ?: return@forEach
            val annotation = uMethod.findAnnotation(KoraAnnotations.KAFKA_LISTENER) ?: return@forEach
            val configPath = AttributeValueResolver.resolveStringAttributeValue(annotation, "value") ?: return@forEach
            val classFqn = method.containingClass?.qualifiedName ?: return@forEach
            byConfigPath.getOrPut(configPath) { mutableListOf() }.add(EndpointTarget.method(classFqn, method.name))
        }

        // Class-level listeners (older Kora): navigate to the class itself.
        AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).forEach { psiClass ->
            ProgressManager.checkCanceled()
            val uClass = psiClass.toUElement() as? UClass ?: return@forEach
            val annotation = uClass.findAnnotation(KoraAnnotations.KAFKA_LISTENER) ?: return@forEach
            val configPath = AttributeValueResolver.resolveStringAttributeValue(annotation, "value") ?: return@forEach
            val classFqn = psiClass.qualifiedName ?: return@forEach
            byConfigPath.getOrPut(configPath) { mutableListOf() }.add(EndpointTarget(classFqn, null, null, 0))
        }

        return byConfigPath.map { (configPath, targets) -> KafkaConsumer(configPath, targets) }.sortedBy { it.configPath }
    }
}
