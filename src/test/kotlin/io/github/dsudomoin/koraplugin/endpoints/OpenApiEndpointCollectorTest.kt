package io.github.dsudomoin.koraplugin.endpoints

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class OpenApiEndpointCollectorTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private val petstoreYaml = """
        openapi: 3.0.0
        info:
          title: Pets
          version: 1.0.0
        paths:
          /pets:
            get:
              operationId: listPets
              summary: List all pets
            post:
              operationId: createPet
          /pets/{id}:
            delete:
              operationId: deletePet
              summary: Delete a pet
        components:
          schemas:
            Pet:
              type: object
    """.trimIndent()

    fun `test parses yaml spec`() {
        val file = myFixture.configureByText("api.yaml", petstoreYaml).virtualFile

        val endpoints = OpenApiEndpointCollector.parseSpec(project, file)
        assertEquals(3, endpoints.size)
        assertTrue(endpoints.all { it.sources == setOf(EndpointSource.OPENAPI) })

        val listPets = endpoints.first { it.operationId == "listPets" }
        assertEquals("GET", listPets.verb)
        assertEquals("/pets", listPets.path)
        assertEquals("List all pets", listPets.summary)
        val specTarget = listPets.targets.single()
        assertNotNull(specTarget.specFileUrl)
        assertTrue(specTarget.offset > 0)

        assertEquals("DELETE", endpoints.first { it.operationId == "deletePet" }.verb)
        assertEquals("/pets/{id}", endpoints.first { it.operationId == "deletePet" }.path)
    }

    fun `test parses json spec`() {
        val file = myFixture.configureByText(
            "api.json",
            """
            {
              "openapi": "3.0.0",
              "paths": {
                "/pets": {
                  "get": {
                    "operationId": "listPets",
                    "summary": "List all pets"
                  },
                  "post": {
                    "operationId": "createPet"
                  }
                }
              }
            }
            """.trimIndent(),
        ).virtualFile

        val endpoints = OpenApiEndpointCollector.parseSpec(project, file)
        assertEquals(2, endpoints.size)

        val listPets = endpoints.first { it.operationId == "listPets" }
        assertEquals("GET", listPets.verb)
        assertEquals("/pets", listPets.path)
        assertEquals("List all pets", listPets.summary)
    }

    fun `test malformed and empty specs yield no endpoints`() {
        val malformed = myFixture.configureByText("broken.yaml", "paths: [unclosed").virtualFile
        assertTrue(OpenApiEndpointCollector.parseSpec(project, malformed).isEmpty())

        val noPaths = myFixture.configureByText("empty.yaml", "openapi: 3.0.0\ninfo:\n  title: X\n").virtualFile
        assertTrue(OpenApiEndpointCollector.parseSpec(project, noPaths).isEmpty())
    }

    fun `test locateSpecFiles finds only files under openapi directory`() {
        myFixture.addFileToProject("src/main/resources/openapi/petstore.yaml", petstoreYaml)
        myFixture.addFileToProject("src/main/resources/application.yml", "kora: {}")

        val files = OpenApiEndpointCollector.locateSpecFiles(project)
        assertEquals(1, files.size)
        assertTrue(files.single().path.endsWith("openapi/petstore.yaml"))
    }

    fun `test collect attaches delegate implementation target by operationId`() {
        myFixture.configureByFiles("ru/tinkoff/kora/common/Component.java")

        myFixture.addFileToProject("src/main/resources/openapi/petstore.yaml", petstoreYaml)
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

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val endpoints = OpenApiEndpointCollector.collect(project)
        val listPets = endpoints.first { it.operationId == "listPets" }

        val codeTarget = listPets.targets.first { it.classFqn != null }
        assertEquals("PetApiDelegateImpl", codeTarget.classFqn)
        assertEquals("listPets", codeTarget.methodName)
        // The spec location is always kept as a fallback target.
        assertTrue(listPets.targets.any { it.specFileUrl != null })
        // Endpoints without a matching delegate method navigate to the spec only.
        val createPet = endpoints.first { it.operationId == "createPet" }
        assertTrue(createPet.targets.all { it.specFileUrl != null })
    }

    fun `test collect applies generator prefixPath from build script`() {
        myFixture.configureByFiles("ru/tinkoff/kora/common/Component.java")

        myFixture.addFileToProject(
            "build.gradle.kts",
            """
            openApi {
                prefixPath.set("/api")
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("src/main/resources/openapi/petstore.yaml", petstoreYaml)

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val endpoints = OpenApiEndpointCollector.collect(project)
        assertEquals(3, endpoints.size)
        assertEquals("/api/pets", endpoints.first { it.operationId == "listPets" }.path)
        assertEquals("/api/pets/{id}", endpoints.first { it.operationId == "deletePet" }.path)
    }

    fun `test collect attaches kotlin delegate implementation target`() {
        myFixture.configureByFiles("ru/tinkoff/kora/common/Component.java")

        myFixture.addFileToProject("src/main/resources/openapi/petstore.yaml", petstoreYaml)
        myFixture.addFileToProject(
            "PetApiDelegate.java",
            """
            public interface PetApiDelegate {
                String listPets();
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "PetApiDelegateImpl.kt",
            """
            import ru.tinkoff.kora.common.Component

            @Component
            class PetApiDelegateImpl : PetApiDelegate {
                override fun listPets(): String = ""
            }
            """.trimIndent(),
        )

        val endpoints = OpenApiEndpointCollector.collect(project)
        val listPets = endpoints.first { it.operationId == "listPets" }
        val codeTarget = listPets.targets.first { it.classFqn != null }
        assertEquals("PetApiDelegateImpl", codeTarget.classFqn)
        assertEquals("listPets", codeTarget.methodName)
    }
}
