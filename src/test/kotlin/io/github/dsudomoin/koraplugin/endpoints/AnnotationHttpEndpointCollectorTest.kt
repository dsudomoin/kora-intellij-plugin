package io.github.dsudomoin.koraplugin.endpoints

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AnnotationHttpEndpointCollectorTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun configureHttpAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/http/common/annotation/HttpRoute.java",
            "ru/tinkoff/kora/http/server/common/annotation/HttpController.java",
            "ru/tinkoff/kora/http/common/HttpMethod.java",
        )
    }

    fun `test collects endpoints from annotated java controller`() {
        configureHttpAnnotations()

        myFixture.addFileToProject(
            "PetController.java",
            """
            import ru.tinkoff.kora.http.common.annotation.HttpRoute;
            import ru.tinkoff.kora.http.server.common.annotation.HttpController;

            @HttpController
            public class PetController {
                @HttpRoute(method = "GET", path = "/pets")
                public String list() { return ""; }

                @HttpRoute(method = "POST", path = "/pets")
                public String create() { return ""; }

                public String notARoute() { return ""; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val endpoints = AnnotationHttpEndpointCollector.collect(project)
        assertEquals(2, endpoints.size)
        assertTrue(endpoints.all { it.sources == setOf(EndpointSource.ANNOTATION) })

        val list = endpoints.first { it.targets.single().methodName == "list" }
        assertEquals("GET", list.verb)
        assertEquals("/pets", list.path)
        assertEquals("PetController", list.targets.single().classFqn)
    }

    fun `test controller value is joined as path prefix`() {
        configureHttpAnnotations()

        myFixture.addFileToProject(
            "ApiController.java",
            """
            import ru.tinkoff.kora.http.common.annotation.HttpRoute;
            import ru.tinkoff.kora.http.server.common.annotation.HttpController;

            @HttpController("/api/v1/")
            public class ApiController {
                @HttpRoute(method = "GET", path = "//pets")
                public String list() { return ""; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val endpoints = AnnotationHttpEndpointCollector.collect(project)
        assertEquals(1, endpoints.size)
        assertEquals("/api/v1/pets", endpoints.single().path)
    }

    fun `test verb resolved from HttpMethod constant reference`() {
        configureHttpAnnotations()

        myFixture.addFileToProject(
            "ConstantVerbController.java",
            """
            import ru.tinkoff.kora.http.common.HttpMethod;
            import ru.tinkoff.kora.http.common.annotation.HttpRoute;
            import ru.tinkoff.kora.http.server.common.annotation.HttpController;

            @HttpController
            public class ConstantVerbController {
                @HttpRoute(method = HttpMethod.GET, path = "/constant")
                public String get() { return ""; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val endpoints = AnnotationHttpEndpointCollector.collect(project)
        assertEquals(1, endpoints.size)
        assertEquals("GET", endpoints.single().verb)
        assertEquals("/constant", endpoints.single().path)
    }

    fun `test collects endpoints from kotlin controller`() {
        configureHttpAnnotations()

        myFixture.configureByText(
            "KtController.kt",
            """
            import ru.tinkoff.kora.http.common.annotation.HttpRoute
            import ru.tinkoff.kora.http.server.common.annotation.HttpController

            @HttpController("/kt")
            class KtController {
                @HttpRoute(method = "get", path = "/items")
                fun items(): String = ""
            }
            """.trimIndent(),
        )
        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val endpoints = AnnotationHttpEndpointCollector.collect(project)
        assertEquals(1, endpoints.size)
        val endpoint = endpoints.single()
        assertEquals("GET", endpoint.verb)
        assertEquals("/kt/items", endpoint.path)
        assertEquals("items", endpoint.targets.single().methodName)
    }

    fun `test kotlin const route path resolves to constant value`() {
        configureHttpAnnotations()
        myFixture.addFileToProject(
            "Routes.kt",
            """
            object Routes {
                const val PETS = "/pets"
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "KtConstController.kt",
            """
            import ru.tinkoff.kora.http.common.annotation.HttpRoute
            import ru.tinkoff.kora.http.server.common.annotation.HttpController

            @HttpController("/api")
            class KtConstController {
                @HttpRoute(method = "get", path = Routes.PETS)
                fun pets(): String = ""
            }
            """.trimIndent(),
        )
        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val endpoints = AnnotationHttpEndpointCollector.collect(project)
        assertEquals(1, endpoints.size)
        // The constant's value, not its name ("PETS").
        assertEquals("/api/pets", endpoints.single().path)
    }

    fun `test returns empty when no controllers exist`() {
        configureHttpAnnotations()

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        assertTrue(AnnotationHttpEndpointCollector.collect(project).isEmpty())
    }

    fun `test returns empty when kora http annotations are not resolvable`() {
        // No annotation stubs configured — @HttpController cannot resolve to a class.
        myFixture.addFileToProject(
            "LonelyController.java",
            """
            public class LonelyController {
                public String list() { return ""; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        assertTrue(AnnotationHttpEndpointCollector.collect(project).isEmpty())
    }
}
