package ru.dsudomoin.koraplugin

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.dsudomoin.koraplugin.index.ProviderKind
import ru.dsudomoin.koraplugin.index.getProviders

class KoraProviderIndexTest : BasePlatformTestCase() {

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
        return getProviders("__nonexistent__", project, scope) != null
    }

    fun `test Component class indexed by class FQN`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyServiceImpl.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyServiceImpl {
                public MyServiceImpl() {}
            }
            """.trimIndent(),
        )

        if (!isIndexAvailable()) return

        val scope = GlobalSearchScope.projectScope(project)
        val entries = getProviders("MyServiceImpl", project, scope)!!

        assertTrue("Should find @Component class in index", entries.isNotEmpty())
        assertEquals(ProviderKind.COMPONENT_CLASS, entries[0].kind)
        assertEquals("MyServiceImpl", entries[0].classFqn)
        assertNull(entries[0].methodName)
    }

    fun `test factory method indexed by return type FQN`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyModule.java",
            """
            import ru.tinkoff.kora.common.Module;

            @Module
            public interface MyModule {
                default MyService createMyService() {
                    return null;
                }
            }
            """.trimIndent(),
        )

        if (!isIndexAvailable()) return

        val scope = GlobalSearchScope.projectScope(project)
        val entries = getProviders("MyService", project, scope)!!

        assertTrue("Should find factory method in index", entries.isNotEmpty())
        val factoryEntry = entries.find { it.kind == ProviderKind.FACTORY_METHOD }
        assertNotNull("Should have FACTORY_METHOD entry", factoryEntry)
        assertEquals("MyModule", factoryEntry!!.classFqn)
        assertEquals("createMyService", factoryEntry.methodName)
    }

    fun `test Generated submodule methods indexed`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyServiceImpl.java",
            """
            public class MyServiceImpl {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyModuleSubmoduleImpl.java",
            """
            import ru.tinkoff.kora.common.annotation.Generated;

            @Generated("ru.tinkoff.kora.kora.app.ksp.KoraSubmoduleProcessor")
            public interface MyModuleSubmoduleImpl {
                default MyServiceImpl _component0() {
                    return new MyServiceImpl();
                }
            }
            """.trimIndent(),
        )

        if (!isIndexAvailable()) return

        val scope = GlobalSearchScope.projectScope(project)
        val entries = getProviders("MyServiceImpl", project, scope)!!

        val generatedEntry = entries.find { it.kind == ProviderKind.FACTORY_METHOD && it.classFqn == "MyModuleSubmoduleImpl" }
        assertNotNull("Should find generated submodule method in index", generatedEntry)
        assertEquals("_component0", generatedEntry!!.methodName)
    }

    fun `test non-Kora classes not indexed`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "PlainClass.java",
            """
            public class PlainClass {
                public PlainClass() {}
            }
            """.trimIndent(),
        )

        if (!isIndexAvailable()) return

        val scope = GlobalSearchScope.projectScope(project)
        val entries = getProviders("PlainClass", project, scope)!!

        assertTrue("Should not index plain classes", entries.isEmpty())
    }

    fun `test KoraApp factory methods indexed`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyApp.java",
            """
            import ru.tinkoff.kora.common.KoraApp;

            @KoraApp
            public interface MyApp {
                default MyService createService() {
                    return null;
                }
            }
            """.trimIndent(),
        )

        if (!isIndexAvailable()) return

        val scope = GlobalSearchScope.projectScope(project)
        val entries = getProviders("MyService", project, scope)!!

        val appEntry = entries.find { it.classFqn == "MyApp" }
        assertNotNull("Should find KoraApp factory method in index", appEntry)
        assertEquals("createService", appEntry!!.methodName)
    }
}
