package io.github.dsudomoin.koraplugin.endpoints

import com.intellij.json.JsonLanguage
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.yaml.YAMLLanguage
import java.util.function.Consumer

/**
 * Merges both endpoint strategies (annotation-based and OpenAPI-spec-based) with dedup
 * by (verb, path), plus Kafka topics. The snapshot is cached on the project and invalidated
 * by Java/Kotlin PSI changes, spec-file PSI changes (YAML/JSON) and VFS changes.
 */
object KoraEndpointsAggregator {

    private val LOG = Logger.getInstance(KoraEndpointsAggregator::class.java)

    /** Bumped by the Refresh action: the cached snapshot is dropped even if nothing else changed. */
    private val refreshTracker = SimpleModificationTracker()

    fun collectSnapshot(project: Project): KoraEndpointsSnapshot =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                doCollect(project),
                PsiModificationTracker.getInstance(project).forLanguage(JavaLanguage.INSTANCE),
                PsiModificationTracker.getInstance(project).forLanguage(KotlinLanguage.INSTANCE),
                PsiModificationTracker.getInstance(project).forLanguage(YAMLLanguage.INSTANCE),
                PsiModificationTracker.getInstance(project).forLanguage(JsonLanguage.INSTANCE),
                VirtualFileManager.getInstance(),
                refreshTracker,
            )
        }

    fun invalidate() = refreshTracker.incModificationCount()

    /**
     * Background collection off the EDT. A nonBlocking read action is used instead of
     * Task.Backgroundable so that automatic refreshes do not flash a progress bar;
     * the read action still never blocks the EDT and waits for smart mode.
     *
     * [onResult] receives `null` when collection failed (the failure is logged here):
     * the non-blocking machinery reports errors without invoking the UI callback,
     * so failures are funneled through the value to let the caller reset its state.
     */
    fun collectAsync(
        project: Project,
        owner: Disposable,
        onResult: (KoraEndpointsSnapshot?) -> Unit,
    ) {
        ReadAction.nonBlocking<KoraEndpointsSnapshot?> {
            try {
                collectSnapshot(project)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                LOG.warn("Kora endpoint collection failed", t)
                null
            }
        }
            .inSmartMode(project)
            .expireWith(owner)
            .coalesceBy(javaClass, project)
            .finishOnUiThread(ModalityState.defaultModalityState(), Consumer { onResult(it) })
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Cheap change detector for polling: PSI changes + VFS changes (spec files). */
    fun modificationStamp(project: Project): Long =
        PsiModificationTracker.getInstance(project).modificationCount * 31L +
            VirtualFileManager.getInstance().modificationCount

    private fun doCollect(project: Project): KoraEndpointsSnapshot {
        // OpenAPI first: its operationId/summary are richer and win the merge.
        val openApiEndpoints = OpenApiEndpointCollector.collect(project)
        ProgressManager.checkCanceled()
        val annotatedEndpoints = AnnotationHttpEndpointCollector.collect(project)
        ProgressManager.checkCanceled()
        val consumers = KafkaTopicCollector.collect(project)
        ProgressManager.checkCanceled()
        val publishers = KafkaPublisherCollector.collect(project)
        ProgressManager.checkCanceled()
        val entities = DbEntityCollector.collect(project)

        val merged = LinkedHashMap<String, KoraEndpoint>()
        for (endpoint in openApiEndpoints + annotatedEndpoints) {
            val key = "${endpoint.verb} ${endpoint.path}"
            val existing = merged[key]
            if (existing == null) {
                merged[key] = endpoint
                continue
            }
            merged[key] = existing.copy(
                sources = existing.sources + endpoint.sources,
                targets = mergeTargets(existing, endpoint),
                operationId = existing.operationId ?: endpoint.operationId,
                summary = existing.summary ?: endpoint.summary,
            )
        }

        return KoraEndpointsSnapshot(
            endpoints = merged.values.sortedWith(compareBy({ it.path }, { it.verb })),
            consumers = consumers,
            publishers = publishers,
            entities = entities,
        )
    }

    /** Code targets (deterministically sorted) first, the spec location last. */
    private fun mergeTargets(existing: KoraEndpoint, other: KoraEndpoint): List<EndpointTarget> {
        val codeTargets = (existing.targets + other.targets)
            .filter { it.classFqn != null }
            .distinct()
            .sortedWith(compareBy({ it.classFqn }, { it.methodName }))
        val specTargets = (existing.targets + other.targets).filter { it.classFqn == null }.distinct()
        return codeTargets + specTargets
    }
}
