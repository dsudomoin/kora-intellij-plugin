package ru.dsudomoin.koraplugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.dsudomoin.koraplugin.config.KoraConfigAnnotationRegistry

class AnnotationConfigNavigationTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun configureResilienceAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/resilient/retry/annotation/Retry.java",
            "ru/tinkoff/kora/resilient/circuitbreaker/annotation/CircuitBreaker.java",
            "ru/tinkoff/kora/resilient/timeout/annotation/Timeout.java",
            "ru/tinkoff/kora/resilient/fallback/annotation/Fallback.java",
        )
    }

    private fun configureCacheAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/cache/annotation/Cacheable.java",
        )
    }

    private fun configureHttpClientAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/http/client/common/annotation/HttpClient.java",
        )
    }

    private fun configureKafkaAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/kafka/common/annotation/KafkaListener.java",
        )
    }

    private fun configureSchedulingAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/scheduling/common/annotation/ScheduleAtFixedRate.java",
            "ru/tinkoff/kora/scheduling/common/annotation/ScheduleWithFixedDelay.java",
            "ru/tinkoff/kora/scheduling/common/annotation/ScheduleOnce.java",
            "ru/tinkoff/kora/scheduling/common/annotation/ScheduleWithCron.java",
        )
    }

    fun `test findAnnotatedElements finds Retry annotated method`() {
        configureResilienceAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            import ru.tinkoff.kora.resilient.retry.annotation.Retry;

            public class MyService {
                @Retry("myRetry")
                public String doSomething() { return ""; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val targets = KoraConfigAnnotationRegistry.findAnnotatedElements(project, "resilient.retry.myRetry")
        assertEquals(1, targets.size)
        assertEquals("doSomething", (targets[0] as com.intellij.psi.PsiMethod).name)
    }

    fun `test findAnnotatedElements finds CircuitBreaker annotated method`() {
        configureResilienceAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            import ru.tinkoff.kora.resilient.circuitbreaker.annotation.CircuitBreaker;

            public class MyService {
                @CircuitBreaker("myCB")
                public String call() { return ""; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val targets = KoraConfigAnnotationRegistry.findAnnotatedElements(project, "resilient.circuitbreaker.myCB")
        assertEquals(1, targets.size)
        assertEquals("call", (targets[0] as com.intellij.psi.PsiMethod).name)
    }

    fun `test findAnnotatedElements returns empty for non-matching config path`() {
        configureResilienceAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            import ru.tinkoff.kora.resilient.retry.annotation.Retry;

            public class MyService {
                @Retry("myRetry")
                public String doSomething() { return ""; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val targets = KoraConfigAnnotationRegistry.findAnnotatedElements(project, "resilient.retry.otherRetry")
        assertTrue(targets.isEmpty())
    }

    fun `test findAnnotatedElements finds Cacheable annotated method`() {
        configureCacheAnnotations()

        myFixture.addFileToProject(
            "CacheService.java",
            """
            import ru.tinkoff.kora.cache.annotation.Cacheable;

            public class CacheService {
                @Cacheable("userCache")
                public String getUser() { return ""; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val caffeine = KoraConfigAnnotationRegistry.findAnnotatedElements(project, "cache.caffeine.userCache")
        assertEquals(1, caffeine.size)

        val redis = KoraConfigAnnotationRegistry.findAnnotatedElements(project, "cache.redis.userCache")
        assertEquals(1, redis.size)
    }

    fun `test findAnnotatedElements finds HttpClient annotated class`() {
        configureHttpClientAnnotations()

        myFixture.addFileToProject(
            "MyClient.java",
            """
            import ru.tinkoff.kora.http.client.common.annotation.HttpClient;

            @HttpClient(configPath = "myApi")
            public interface MyClient {
                String get();
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val targets = KoraConfigAnnotationRegistry.findAnnotatedElements(project, "httpClient.myApi")
        assertEquals(1, targets.size)
    }

    fun `test resolveConfigPaths for Retry via findAnnotatedElements`() {
        configureResilienceAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            import ru.tinkoff.kora.resilient.retry.annotation.Retry;

            public class MyService {
                @Retry("myRetry")
                public String doSomething() { return ""; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        // Verify config path resolution works by checking findAnnotatedElements
        val retryTargets = KoraConfigAnnotationRegistry.findAnnotatedElements(project, "resilient.retry.myRetry")
        assertEquals("Should find annotated method via retry config path", 1, retryTargets.size)

        // And verify non-matching path returns empty
        val noTargets = KoraConfigAnnotationRegistry.findAnnotatedElements(project, "resilient.retry.otherRetry")
        assertTrue("Should not match different retry name", noTargets.isEmpty())
    }

    fun `test resolveConfigPaths for Cacheable returns caffeine and redis paths`() {
        configureCacheAnnotations()

        myFixture.addFileToProject(
            "CacheService.java",
            """
            import ru.tinkoff.kora.cache.annotation.Cacheable;

            public class CacheService {
                @Cacheable("userCache")
                public String getUser() { return ""; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        // @Cacheable("userCache") should be findable via both caffeine and redis paths
        val caffeineTargets = KoraConfigAnnotationRegistry.findAnnotatedElements(project, "cache.caffeine.userCache")
        assertEquals("Should find via caffeine path", 1, caffeineTargets.size)

        val redisTargets = KoraConfigAnnotationRegistry.findAnnotatedElements(project, "cache.redis.userCache")
        assertEquals("Should find via redis path", 1, redisTargets.size)
    }

    fun `test findAnnotatedElements with ScheduleAtFixedRate`() {
        configureSchedulingAnnotations()

        myFixture.addFileToProject(
            "MyScheduler.java",
            """
            import ru.tinkoff.kora.scheduling.common.annotation.ScheduleAtFixedRate;

            public class MyScheduler {
                @ScheduleAtFixedRate(config = "scheduling.myJob")
                public void runJob() {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val targets = KoraConfigAnnotationRegistry.findAnnotatedElements(project, "scheduling.myJob")
        assertEquals(1, targets.size)
        assertEquals("runJob", (targets[0] as com.intellij.psi.PsiMethod).name)
    }

    fun `test findAnnotatedElements with ScheduleWithCron`() {
        configureSchedulingAnnotations()

        myFixture.addFileToProject(
            "MyCronJob.java",
            """
            import ru.tinkoff.kora.scheduling.common.annotation.ScheduleWithCron;

            public class MyCronJob {
                @ScheduleWithCron(config = "scheduling.cronJob")
                public void execute() {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val targets = KoraConfigAnnotationRegistry.findAnnotatedElements(project, "scheduling.cronJob")
        assertEquals(1, targets.size)
        assertEquals("execute", (targets[0] as com.intellij.psi.PsiMethod).name)
    }

    fun `test findAnnotatedElements with KafkaListener`() {
        configureKafkaAnnotations()

        myFixture.addFileToProject(
            "MyConsumer.java",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaListener;

            public class MyConsumer {
                @KafkaListener("path.to.kafka.consumer")
                public void consume(String msg) {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val targets = KoraConfigAnnotationRegistry.findAnnotatedElements(project, "path.to.kafka.consumer")
        assertEquals(1, targets.size)
        assertEquals("consume", (targets[0] as com.intellij.psi.PsiMethod).name)
    }
}
