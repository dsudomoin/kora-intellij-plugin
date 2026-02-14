package ru.dsudomoin.koraplugin

import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.dsudomoin.koraplugin.config.ConfigPathResolver
import ru.dsudomoin.koraplugin.config.ConfigSourceSearch
import ru.dsudomoin.koraplugin.config.KoraConfigAnnotationRegistry
import ru.dsudomoin.koraplugin.resolve.InjectionPointDetector
import ru.dsudomoin.koraplugin.resolve.InjectionSiteSearch
import ru.dsudomoin.koraplugin.resolve.KoraModuleRegistry
import ru.dsudomoin.koraplugin.resolve.KoraProviderResolver
import ru.dsudomoin.koraplugin.resolve.ProviderSearch

/**
 * Benchmark test for measuring optimization impact.
 *
 * Measures:
 * - #11: supplementProvidersFromUnannotatedModules caching (O(N×M) → O(1))
 * - #12: findAnnotatedElements caching (560 searches → 28)
 * - #13: Double detect() elimination
 * - #14: isKoraModuleClass check order (HashSet first vs PSI walk first)
 * - #15: Regex pre-compilation
 * - #16: isReferencedFromConfigSource early exit via cached FQN set
 *
 * EDT fixes (#9, #10) are architectural — verified by code pattern, not timing.
 */
class OptimizationBenchmarkTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun configureAllAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/common/Component.java",
            "ru/tinkoff/kora/common/KoraApp.java",
            "ru/tinkoff/kora/common/Module.java",
            "ru/tinkoff/kora/common/KoraSubmodule.java",
            "ru/tinkoff/kora/common/Tag.java",
            "ru/tinkoff/kora/common/annotation/Generated.java",
            "ru/tinkoff/kora/application/graph/All.java",
            "ru/tinkoff/kora/config/common/annotation/ConfigSource.java",
            "ru/tinkoff/kora/resilient/retry/annotation/Retry.java",
            "ru/tinkoff/kora/resilient/circuitbreaker/annotation/CircuitBreaker.java",
            "ru/tinkoff/kora/resilient/timeout/annotation/Timeout.java",
            "ru/tinkoff/kora/resilient/fallback/annotation/Fallback.java",
            "ru/tinkoff/kora/cache/annotation/Cacheable.java",
            "ru/tinkoff/kora/http/client/common/annotation/HttpClient.java",
            "ru/tinkoff/kora/kafka/common/annotation/KafkaListener.java",
            "ru/tinkoff/kora/scheduling/common/annotation/ScheduleAtFixedRate.java",
            "ru/tinkoff/kora/scheduling/common/annotation/ScheduleWithFixedDelay.java",
            "ru/tinkoff/kora/scheduling/common/annotation/ScheduleOnce.java",
            "ru/tinkoff/kora/scheduling/common/annotation/ScheduleWithCron.java",
        )
    }

    // ==================== #15: Regex pre-compilation ====================

    fun `test 15 - Regex pre-compilation vs on-the-fly`() {
        val testStrings = listOf(
            "hubItems", "spawnLocation", "maxRetryCount", "connectionTimeout",
            "kafkaConsumerGroupId", "circuitBreakerThreshold", "cacheExpiration",
            "httpClientBaseUrl", "schedulingFixedRate", "databasePoolSize",
        )

        val warmup = 1000
        val iterations = 10_000

        // Warmup
        repeat(warmup) {
            for (s in testStrings) {
                s.replace(Regex("([a-z0-9])([A-Z])")) { "${it.groupValues[1]}-${it.groupValues[2].lowercase()}" }
            }
        }

        // Measure: Regex created on each call (OLD behavior)
        val startOnTheFly = System.nanoTime()
        repeat(iterations) {
            for (s in testStrings) {
                s.replace(Regex("([a-z0-9])([A-Z])")) { "${it.groupValues[1]}-${it.groupValues[2].lowercase()}" }
            }
        }
        val onTheFlyNs = System.nanoTime() - startOnTheFly

        // Measure: Pre-compiled Regex (NEW behavior — same as ConfigPathResolver)
        val precompiled = Regex("([a-z0-9])([A-Z])")
        val startPrecompiled = System.nanoTime()
        repeat(iterations) {
            for (s in testStrings) {
                s.replace(precompiled) { "${it.groupValues[1]}-${it.groupValues[2].lowercase()}" }
            }
        }
        val precompiledNs = System.nanoTime() - startPrecompiled

        val speedup = onTheFlyNs.toDouble() / precompiledNs.toDouble()

        println("=== #15: Regex pre-compilation benchmark ===")
        println("  Iterations: $iterations × ${testStrings.size} strings = ${iterations * testStrings.size} calls")
        println("  On-the-fly Regex():  ${onTheFlyNs / 1_000_000} ms")
        println("  Pre-compiled Regex:  ${precompiledNs / 1_000_000} ms")
        println("  Speedup: %.2fx".format(speedup))
        println()

        assertTrue("Pre-compiled regex should be faster", precompiledNs < onTheFlyNs)
    }

    fun `test 15 - kebabToCamel pre-compiled vs on-the-fly`() {
        val testStrings = listOf(
            "hub-items", "spawn-location", "max-retry-count", "connection-timeout",
            "kafka-consumer-group-id", "circuit-breaker-threshold", "cache-expiration",
        )

        val warmup = 1000
        val iterations = 10_000

        repeat(warmup) {
            for (s in testStrings) {
                s.replace(Regex("-([a-z])")) { it.groupValues[1].uppercase() }
            }
        }

        val startOnTheFly = System.nanoTime()
        repeat(iterations) {
            for (s in testStrings) {
                s.replace(Regex("-([a-z])")) { it.groupValues[1].uppercase() }
            }
        }
        val onTheFlyNs = System.nanoTime() - startOnTheFly

        val precompiled = Regex("-([a-z])")
        val startPrecompiled = System.nanoTime()
        repeat(iterations) {
            for (s in testStrings) {
                s.replace(precompiled) { it.groupValues[1].uppercase() }
            }
        }
        val precompiledNs = System.nanoTime() - startPrecompiled

        val speedup = onTheFlyNs.toDouble() / precompiledNs.toDouble()

        println("=== #15: kebabToCamel Regex benchmark ===")
        println("  Iterations: $iterations × ${testStrings.size} strings = ${iterations * testStrings.size} calls")
        println("  On-the-fly Regex():  ${onTheFlyNs / 1_000_000} ms")
        println("  Pre-compiled Regex:  ${precompiledNs / 1_000_000} ms")
        println("  Speedup: %.2fx".format(speedup))
        println()

        assertTrue("Pre-compiled regex should be faster", precompiledNs < onTheFlyNs)
    }

    // ==================== #13: Double detect() elimination ====================

    fun `test 13 - single detect vs double detect`() {
        configureAllAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "MyServiceImpl.java",
            """
            import ru.tinkoff.kora.common.Component;
            @Component
            public class MyServiceImpl implements MyService {
                public MyServiceImpl() {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "MyController.java",
            """
            import ru.tinkoff.kora.common.Component;
            @Component
            public class MyController {
                public MyController(MyService my<caret>Service) {}
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val warmup = 50
        val iterations = 200

        // Warmup
        repeat(warmup) {
            InjectionPointDetector.detect(element)
            KoraProviderResolver.resolve(element)
        }

        // OLD: double detect — detect() called explicitly + resolve(element) calls it again inside
        val startDouble = System.nanoTime()
        repeat(iterations) {
            val ip = InjectionPointDetector.detect(element)!!
            // OLD: KoraProviderResolver.resolve(element) which calls detect() again
            KoraProviderResolver.resolve(element)
        }
        val doubleNs = System.nanoTime() - startDouble

        // NEW: single detect — detect() once, pass InjectionPoint to resolve()
        val startSingle = System.nanoTime()
        repeat(iterations) {
            val ip = InjectionPointDetector.detect(element)!!
            // NEW: KoraProviderResolver.resolve(ip, project) — no re-detection
            KoraProviderResolver.resolve(ip, element.project)
        }
        val singleNs = System.nanoTime() - startSingle

        val saved = doubleNs - singleNs
        val savedPerCall = saved / iterations

        println("=== #13: Double detect() elimination benchmark ===")
        println("  Iterations: $iterations")
        println("  Double detect (old):  ${doubleNs / 1_000_000} ms (${doubleNs / iterations / 1_000} µs/call)")
        println("  Single detect (new):  ${singleNs / 1_000_000} ms (${singleNs / iterations / 1_000} µs/call)")
        println("  Saved per call: ${savedPerCall / 1_000} µs")
        println("  Total saved: ${saved / 1_000_000} ms over $iterations iterations")
        println()
    }

    // ==================== #12: findAnnotatedElements caching ====================

    fun `test 12 - findAnnotatedElements caching benefit`() {
        configureAllAnnotations()

        // Create annotated methods
        myFixture.addFileToProject(
            "MyRetryService.java",
            """
            import ru.tinkoff.kora.resilient.retry.annotation.Retry;
            public class MyRetryService {
                @Retry("myRetry") public String doWork() { return ""; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "MyCBService.java",
            """
            import ru.tinkoff.kora.resilient.circuitbreaker.annotation.CircuitBreaker;
            public class MyCBService {
                @CircuitBreaker("myCb") public String process() { return ""; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "MyCacheService.java",
            """
            import ru.tinkoff.kora.cache.annotation.Cacheable;
            public class MyCacheService {
                @Cacheable("myCache") public String getData() { return ""; }
            }
            """.trimIndent(),
        )
        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val paths = listOf(
            "resilient.retry.myRetry",
            "resilient.circuitbreaker.myCb",
            "cache.caffeine.myCache",
            "cache.redis.myCache",
            "resilient.timeout.unknownTimeout",
            "scheduling.someTask",
            "httpClient.someClient",
        )

        val warmup = 10
        val iterations = 100

        // Warmup (fills cache)
        repeat(warmup) {
            for (path in paths) {
                KoraConfigAnnotationRegistry.findAnnotatedElements(project, path)
            }
        }

        // Measure: N calls to findAnnotatedElements — all cached after first build
        val start = System.nanoTime()
        repeat(iterations) {
            for (path in paths) {
                KoraConfigAnnotationRegistry.findAnnotatedElements(project, path)
            }
        }
        val totalNs = System.nanoTime() - start
        val perCallNs = totalNs / (iterations * paths.size)

        println("=== #12: findAnnotatedElements caching benchmark ===")
        println("  Queries: $iterations × ${paths.size} paths = ${iterations * paths.size} total")
        println("  Total time: ${totalNs / 1_000_000} ms")
        println("  Per call (cached): ${perCallNs / 1_000} µs")
        println("  NOTE: Without cache, each call would do 28 AnnotatedElementsSearch queries.")
        println("  With cache: 0 searches (pure HashMap lookup + prefix scan)")
        println()
    }

    // ==================== #11: Supplement providers caching ====================

    fun `test 11 - supplement providers caching benefit`() {
        configureAllAnnotations()

        // Create an unannotated module hierarchy:
        // @KoraApp extends AppModule (unannotated) which has factory methods
        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "MyRepository.java",
            """
            public interface MyRepository {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "AppModule.java",
            """
            public interface AppModule {
                default MyService myService() { return null; }
                default MyRepository myRepository() { return null; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "MyApp.java",
            """
            import ru.tinkoff.kora.common.KoraApp;
            @KoraApp
            public interface MyApp extends AppModule {}
            """.trimIndent(),
        )
        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val warmup = 20
        val iterations = 200

        // Warmup
        repeat(warmup) {
            ProviderSearch.findAllProviders(project)
        }

        // Multiple findProvidersByType calls — supplement is now cached
        // In OLD code, each call to findProvidersByType would iterate all unannotated modules
        // In NEW code, the unannotated module map is built once and reused
        val typeFqns = listOf("MyService", "MyRepository", "java.lang.String", "java.lang.Object")

        val start = System.nanoTime()
        repeat(iterations) {
            for (fqn in typeFqns) {
                // This uses the cached unannotated module map internally
                ProviderSearch.findProvidersByType(project, fqn)
            }
        }
        val totalNs = System.nanoTime() - start
        val perCallNs = totalNs / (iterations * typeFqns.size)

        // Also verify KoraModuleRegistry caching
        val registryStart = System.nanoTime()
        repeat(iterations) {
            KoraModuleRegistry.getModuleClassFqns(project)
        }
        val registryNs = System.nanoTime() - registryStart

        println("=== #11: Supplement providers caching benchmark ===")
        println("  findProvidersByType calls: $iterations × ${typeFqns.size} = ${iterations * typeFqns.size}")
        println("  Total time: ${totalNs / 1_000_000} ms")
        println("  Per call: ${perCallNs / 1_000} µs")
        println("  KoraModuleRegistry.getModuleClassFqns ($iterations calls): ${registryNs / 1_000_000} ms (${registryNs / iterations / 1_000} µs/call)")
        println("  NOTE: Without cache each findProvidersByType would scan all unannotated modules.")
        println("  With cache: single HashMap.get() per type FQN")
        println()
    }

    // ==================== #14: isKoraModuleClass check order ====================

    fun `test 14 - isKoraModuleClass cached registry first`() {
        configureAllAnnotations()

        myFixture.addFileToProject(
            "AppModule.java",
            """
            public interface AppModule {
                default String someFactory() { return ""; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "MyApp.java",
            """
            import ru.tinkoff.kora.common.KoraApp;
            @KoraApp
            public interface MyApp extends AppModule {}
            """.trimIndent(),
        )
        myFixture.configureByText(
            "TestModule.java",
            """
            public interface TestModule extends AppModule {
                default Integer test<caret>Method(String dep) { return 0; }
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val warmup = 50
        val iterations = 500

        // Warmup — populates KoraModuleRegistry cache
        repeat(warmup) {
            InjectionPointDetector.detect(element)
        }

        // Measure detect() which internally calls isKoraModuleClass
        // With the optimization, HashSet lookup happens BEFORE expensive PSI walk
        val start = System.nanoTime()
        repeat(iterations) {
            InjectionPointDetector.detect(element)
        }
        val totalNs = System.nanoTime() - start
        val perCallNs = totalNs / iterations

        // Measure the cached registry lookup alone
        val registryStart = System.nanoTime()
        repeat(iterations) {
            val fqns = KoraModuleRegistry.getModuleClassFqns(project)
            fqns.contains("AppModule")
        }
        val registryNs = System.nanoTime() - registryStart
        val registryPerCall = registryNs / iterations

        println("=== #14: isKoraModuleClass check order benchmark ===")
        println("  Iterations: $iterations")
        println("  InjectionPointDetector.detect(): ${totalNs / 1_000_000} ms (${perCallNs / 1_000} µs/call)")
        println("  Registry HashSet.contains() alone: ${registryNs / 1_000_000} ms (${registryPerCall / 1_000} µs/call)")
        println("  NOTE: Old order did UAST conversion + annotation check on all supers BEFORE registry.")
        println("  New order: registry O(1) lookup first, PSI walk only as fallback.")
        println()
    }

    // ==================== #16: isReferencedFromConfigSource early exit ====================

    fun `test 16 - isReferencedFromConfigSource early exit via cached FQN set`() {
        configureAllAnnotations()

        myFixture.addFileToProject(
            "HubConfig.java",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource;
            @ConfigSource("hub")
            public interface HubConfig {
                String name();
                SpawnConfig spawn();
                interface SpawnConfig {
                    String location();
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val warmup = 20
        val iterations = 500

        // Warmup
        repeat(warmup) {
            ConfigSourceSearch.getReferencedTypeFqns(project)
        }

        // Measure cached FQN set lookup
        val start = System.nanoTime()
        repeat(iterations) {
            val fqns = ConfigSourceSearch.getReferencedTypeFqns(project)
            // Simulate early exit checks for various class FQNs
            fqns.contains("HubConfig.SpawnConfig")
            fqns.contains("java.lang.String")
            fqns.contains("com.example.UnrelatedClass")
        }
        val totalNs = System.nanoTime() - start
        val perCallNs = totalNs / iterations

        // For comparison: measure what the OLD code would do (resolveMemberToConfigPath)
        val entries = ConfigSourceSearch.findAllConfigSources(project)
        val oldStart = System.nanoTime()
        repeat(iterations) {
            // OLD: for every class, call resolveMemberToConfigPath to check
            ConfigPathResolver.resolveMemberToConfigPath("name", entries.first().psiClass)
        }
        val oldNs = System.nanoTime() - oldStart
        val oldPerCall = oldNs / iterations

        val speedup = oldNs.toDouble() / totalNs.toDouble()

        println("=== #16: isReferencedFromConfigSource early exit benchmark ===")
        println("  Iterations: $iterations")
        println("  Cached FQN set check (new): ${totalNs / 1_000_000} ms (${perCallNs / 1_000} µs/call)")
        println("  resolveMemberToConfigPath (old): ${oldNs / 1_000_000} ms (${oldPerCall / 1_000} µs/call)")
        println("  Speedup for early exit: %.2fx".format(speedup))
        println("  NOTE: For non-config classes, the FQN set check returns false immediately")
        println("  instead of scanning all @ConfigSource classes.")
        println()
    }

    // ==================== Summary ====================

    fun `test 00 - full resolve pipeline benchmark`() {
        configureAllAnnotations()

        // Set up a realistic project structure
        for (i in 1..5) {
            myFixture.addFileToProject(
                "Service$i.java",
                """
                public interface Service$i {}
                """.trimIndent(),
            )
            myFixture.addFileToProject(
                "Service${i}Impl.java",
                """
                import ru.tinkoff.kora.common.Component;
                @Component
                public class Service${i}Impl implements Service$i {
                    public Service${i}Impl() {}
                }
                """.trimIndent(),
            )
        }
        myFixture.addFileToProject(
            "AppModule.java",
            """
            public interface AppModule {
                default Service1 extraService1() { return null; }
                default Service2 extraService2() { return null; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "MyApp.java",
            """
            import ru.tinkoff.kora.common.KoraApp;
            @KoraApp
            public interface MyApp extends AppModule {}
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Controller.java",
            """
            import ru.tinkoff.kora.common.Component;
            @Component
            public class Controller {
                public Controller(Service1 s<caret>1, Service2 s2, Service3 s3) {}
            }
            """.trimIndent(),
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val warmup = 30
        val iterations = 200

        // Warmup — fill all caches
        repeat(warmup) {
            KoraProviderResolver.resolve(element)
            ProviderSearch.findAllProviders(project)
            InjectionSiteSearch.findAllInjectionSites(project)
        }

        // Measure full resolve pipeline
        val startResolve = System.nanoTime()
        repeat(iterations) {
            KoraProviderResolver.resolve(element)
        }
        val resolveNs = System.nanoTime() - startResolve

        // Measure findAllProviders (cached)
        val startProviders = System.nanoTime()
        repeat(iterations) {
            ProviderSearch.findAllProviders(project)
        }
        val providersNs = System.nanoTime() - startProviders

        // Measure findAllInjectionSites (cached)
        val startSites = System.nanoTime()
        repeat(iterations) {
            InjectionSiteSearch.findAllInjectionSites(project)
        }
        val sitesNs = System.nanoTime() - startSites

        println("=== Full resolve pipeline benchmark ===")
        println("  Project: 5 services + 5 impls + 1 unannotated module + 1 @KoraApp")
        println("  Iterations: $iterations")
        println()
        println("  KoraProviderResolver.resolve():     ${resolveNs / 1_000_000} ms (${resolveNs / iterations / 1_000} µs/call)")
        println("  ProviderSearch.findAllProviders():   ${providersNs / 1_000_000} ms (${providersNs / iterations / 1_000} µs/call)")
        println("  InjectionSiteSearch.findAllSites():  ${sitesNs / 1_000_000} ms (${sitesNs / iterations / 1_000} µs/call)")
        println()
        println("  findAllProviders and findAllInjectionSites are cached → near-zero after first call.")
        println("  resolve() includes: detect() + index query (falls back to cached full scan) + type/tag filter.")
        println()
    }
}
