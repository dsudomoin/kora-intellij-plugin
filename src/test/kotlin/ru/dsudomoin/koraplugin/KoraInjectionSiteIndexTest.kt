package ru.dsudomoin.koraplugin

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.dsudomoin.koraplugin.index.KoraInjectionSiteIndex

class KoraInjectionSiteIndexTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun configureAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/common/Component.java",
            "ru/tinkoff/kora/common/KoraApp.java",
            "ru/tinkoff/kora/common/Module.java",
            "ru/tinkoff/kora/common/KoraSubmodule.java",
            "ru/tinkoff/kora/common/Tag.java",
            "ru/tinkoff/kora/common/annotation/Generated.java",
            "ru/tinkoff/kora/application/graph/All.java",
        )
    }

    private fun isIndexAvailable(): Boolean {
        val scope = GlobalSearchScope.projectScope(project)
        return KoraInjectionSiteIndex.getSites("__nonexistent__", project, scope) != null
    }

    fun `test Component constructor params indexed by type FQN`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyController.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyController {
                public MyController(MyService myService) {}
            }
            """.trimIndent(),
        )

        if (!isIndexAvailable()) return

        val scope = GlobalSearchScope.projectScope(project)
        val entries = KoraInjectionSiteIndex.getSites("MyService", project, scope)!!

        assertTrue("Should find constructor param in index", entries.isNotEmpty())
        val entry = entries[0]
        assertEquals("MyController", entry.classFqn)
        assertEquals("myService", entry.paramName)
        assertTrue(entry.isConstructor)
        assertNull(entry.methodName)
    }

    fun `test module factory method params indexed`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyRepository.java",
            """
            public interface MyRepository {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyModule.java",
            """
            import ru.tinkoff.kora.common.Module;

            @Module
            public interface MyModule {
                default MyService createService(MyRepository repo) {
                    return null;
                }
            }
            """.trimIndent(),
        )

        if (!isIndexAvailable()) return

        val scope = GlobalSearchScope.projectScope(project)
        val entries = KoraInjectionSiteIndex.getSites("MyRepository", project, scope)!!

        assertTrue("Should find factory method param in index", entries.isNotEmpty())
        val entry = entries[0]
        assertEquals("MyModule", entry.classFqn)
        assertEquals("createService", entry.methodName)
        assertEquals("repo", entry.paramName)
        assertFalse(entry.isConstructor)
    }

    fun `test All type unwrapping in indexer`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyConsumer.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.application.graph.All;

            @Component
            public class MyConsumer {
                public MyConsumer(All<MyService> allServices) {}
            }
            """.trimIndent(),
        )

        if (!isIndexAvailable()) return

        val scope = GlobalSearchScope.projectScope(project)
        // Should be indexed under "MyService" (unwrapped from All<MyService>)
        val entries = KoraInjectionSiteIndex.getSites("MyService", project, scope)!!

        assertTrue("Should find All<MyService> unwrapped to MyService in index", entries.isNotEmpty())
        val entry = entries[0]
        assertEquals("MyConsumer", entry.classFqn)
        assertEquals("allServices", entry.paramName)
    }

    fun `test non-Kora class params not indexed`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "PlainClass.java",
            """
            public class PlainClass {
                public PlainClass(String someParam) {}
            }
            """.trimIndent(),
        )

        if (!isIndexAvailable()) return

        val scope = GlobalSearchScope.projectScope(project)
        val entries = KoraInjectionSiteIndex.getSites("java.lang.String", project, scope)!!

        assertTrue("Should not index params from plain classes", entries.isEmpty())
    }

    fun `test multiple params from same constructor indexed`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "ServiceA.java",
            """
            public interface ServiceA {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "ServiceB.java",
            """
            public interface ServiceB {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyController.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyController {
                public MyController(ServiceA a, ServiceB b) {}
            }
            """.trimIndent(),
        )

        if (!isIndexAvailable()) return

        val scope = GlobalSearchScope.projectScope(project)
        val entriesA = KoraInjectionSiteIndex.getSites("ServiceA", project, scope)!!
        val entriesB = KoraInjectionSiteIndex.getSites("ServiceB", project, scope)!!

        assertTrue("Should find ServiceA param", entriesA.isNotEmpty())
        assertTrue("Should find ServiceB param", entriesB.isNotEmpty())
        assertEquals("a", entriesA[0].paramName)
        assertEquals("b", entriesB[0].paramName)
    }
}
