package io.github.dsudomoin.koraplugin.endpoints

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KoraEndpointsAggregatorTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun configureStubs() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/common/Component.java",
            "ru/tinkoff/kora/http/common/annotation/HttpRoute.java",
            "ru/tinkoff/kora/http/server/common/annotation/HttpController.java",
        )
    }

    fun `test snapshot includes publishers and entities`() {
        configureStubs()
        myFixture.configureByFiles(
            "ru/tinkoff/kora/kafka/common/annotation/KafkaPublisher.java",
            "ru/tinkoff/kora/database/common/annotation/Table.java",
        )

        myFixture.addFileToProject(
            "EventPublisher.java",
            """
            import ru.tinkoff.kora.kafka.common.annotation.KafkaPublisher;

            @KafkaPublisher("kafka.producer")
            public interface EventPublisher {
                @KafkaPublisher.Topic("events")
                void send(String key, String event);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "User.java",
            """
            import ru.tinkoff.kora.database.common.annotation.Table;

            @Table("users")
            public record User(Long id) {}
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val snapshot = KoraEndpointsAggregator.collectSnapshot(project)
        assertTrue(snapshot.endpoints.isEmpty())
        assertEquals(1, snapshot.publishers.size)
        assertEquals("EventPublisher", snapshot.publishers.single().publisherFqn)
        assertEquals(1, snapshot.entities.size)
        assertEquals("users", snapshot.entities.single().tableName)
        assertFalse(snapshot.isEmpty)
    }

    fun `test merges annotation and openapi endpoints by verb and path`() {
        configureStubs()

        myFixture.addFileToProject(
            "src/main/resources/openapi/petstore.yaml",
            """
            openapi: 3.0.0
            paths:
              /pets:
                get:
                  operationId: listPets
                  summary: List all pets
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "PetApiDelegate.java",
            """
            public interface PetApiDelegate {
                String listPets();
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "PetApiDelegateImpl.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class PetApiDelegateImpl implements PetApiDelegate {
                public String listPets() { return ""; }
            }
            """.trimIndent(),
        )
        // Same verb+path declared via annotations (lowercase verb — must normalize and dedup).
        myFixture.addFileToProject(
            "AnnotatedPetController.java",
            """
            import ru.tinkoff.kora.http.common.annotation.HttpRoute;
            import ru.tinkoff.kora.http.server.common.annotation.HttpController;

            @HttpController
            public class AnnotatedPetController {
                @HttpRoute(method = "get", path = "/pets")
                public String list() { return ""; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val snapshot = KoraEndpointsAggregator.collectSnapshot(project)
        assertEquals(1, snapshot.endpoints.size)

        val endpoint = snapshot.endpoints.single()
        assertEquals(setOf(EndpointSource.ANNOTATION, EndpointSource.OPENAPI), endpoint.sources)
        assertEquals("GET", endpoint.verb)
        assertEquals("/pets", endpoint.path)
        assertEquals("listPets", endpoint.operationId)
        assertEquals("List all pets", endpoint.summary)

        // Three navigation targets: delegate impl method, annotated controller method, spec location.
        assertEquals(3, endpoint.targets.size)
        val codeTargets = endpoint.targets.filter { it.classFqn != null }
        assertEquals(
            setOf("PetApiDelegateImpl", "AnnotatedPetController"),
            codeTargets.map { it.classFqn }.toSet(),
        )
        assertTrue(endpoint.targets.any { it.specFileUrl != null })
    }

    fun `test different paths are not merged`() {
        configureStubs()

        myFixture.addFileToProject(
            "src/main/resources/openapi/api.yaml",
            """
            openapi: 3.0.0
            paths:
              /pets:
                get:
                  operationId: listPets
              /cats:
                get:
                  operationId: listCats
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "DogController.java",
            """
            import ru.tinkoff.kora.http.common.annotation.HttpRoute;
            import ru.tinkoff.kora.http.server.common.annotation.HttpController;

            @HttpController
            public class DogController {
                @HttpRoute(method = "GET", path = "/dogs")
                public String list() { return ""; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val snapshot = KoraEndpointsAggregator.collectSnapshot(project)
        assertEquals(3, snapshot.endpoints.size)
        assertEquals(listOf("/cats", "/dogs", "/pets"), snapshot.endpoints.map { it.path })
    }

    fun `test snapshot is cached until psi changes`() {
        configureStubs()

        myFixture.addFileToProject(
            "CachedController.java",
            """
            import ru.tinkoff.kora.http.common.annotation.HttpRoute;
            import ru.tinkoff.kora.http.server.common.annotation.HttpController;

            @HttpController
            public class CachedController {
                @HttpRoute(method = "GET", path = "/cached")
                public String list() { return ""; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val first = KoraEndpointsAggregator.collectSnapshot(project)
        val second = KoraEndpointsAggregator.collectSnapshot(project)
        assertSame(first, second)

        // A PSI change (new file) must invalidate the cached snapshot.
        myFixture.configureByText("Other.java", "class Other {}")
        val third = KoraEndpointsAggregator.collectSnapshot(project)
        assertNotSame(first, third)
        assertEquals(first.endpoints, third.endpoints)
    }
}
