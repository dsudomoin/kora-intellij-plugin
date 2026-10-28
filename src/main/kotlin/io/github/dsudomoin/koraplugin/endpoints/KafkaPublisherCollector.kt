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
 * Outgoing Kafka topics: @KafkaPublisher interfaces with @KafkaPublisher.Topic methods.
 * The publisher `value` attribute is the producer config path; each @Topic value is the config
 * path of an outgoing topic. Methods without @Topic are not topic producers and are skipped.
 */
object KafkaPublisherCollector {

    fun collect(project: Project): List<KafkaPublisher> {
        val annotationClass = JavaPsiFacade.getInstance(project)
            .findClass(KoraAnnotations.KAFKA_PUBLISHER, GlobalSearchScope.allScope(project))
            ?: return emptyList()

        val publishers = mutableListOf<KafkaPublisher>()
        AnnotatedElementsSearch.searchPsiClasses(annotationClass, GlobalSearchScope.projectScope(project)).forEach { psiClass ->
            ProgressManager.checkCanceled()
            val uClass = psiClass.toUElement() as? UClass ?: return@forEach
            val publisherFqn = psiClass.qualifiedName ?: return@forEach
            val configPath = uClass.findAnnotation(KoraAnnotations.KAFKA_PUBLISHER)
                ?.let { AttributeValueResolver.resolveStringAttributeValue(it, "value") }

            val topics = uClass.methods.mapNotNull { uMethod ->
                val topicAnnotation = uMethod.findAnnotation(KoraAnnotations.KAFKA_PUBLISHER_TOPIC) ?: return@mapNotNull null
                val configPath = AttributeValueResolver.resolveStringAttributeValue(topicAnnotation, "value") ?: return@mapNotNull null
                KafkaPublisherTopic(configPath, EndpointTarget.method(publisherFqn, uMethod.name))
            }
            if (topics.isNotEmpty()) {
                publishers.add(KafkaPublisher(publisherFqn, configPath, topics))
            }
        }
        return publishers.sortedBy { it.publisherFqn }
    }
}
