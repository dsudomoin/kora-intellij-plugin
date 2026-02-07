package ru.dsudomoin.koraplugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.dsudomoin.koraplugin.resolve.InjectionSiteSearch

class InjectionSiteSearchTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun configureAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/common/Component.java",
            "ru/tinkoff/kora/common/KoraApp.java",
            "ru/tinkoff/kora/common/Module.java",
            "ru/tinkoff/kora/common/KoraSubmodule.java",
            "ru/tinkoff/kora/common/Tag.java",
            "ru/tinkoff/kora/application/graph/All.java",
        )
    }

    fun `test finds constructor parameter injection sites`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyController.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyController {
                public MyController(MyService myService) {}
            }
            """.trimIndent(),
        )

        val sites = InjectionSiteSearch.findAllInjectionSites(project)

        assertTrue(
            "Should find at least one injection site for MyService",
            sites.any { it.requiredType.canonicalText == "MyService" },
        )
    }

    fun `test finds factory method parameter injection sites`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyRepository.java",
            """
            public interface MyRepository {}
            """.trimIndent(),
        )

        myFixture.configureByText(
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

        val sites = InjectionSiteSearch.findAllInjectionSites(project)

        assertTrue(
            "Should find injection site for MyRepository in factory method",
            sites.any { it.requiredType.canonicalText == "MyRepository" },
        )
    }

    fun `test extracts tags from injection sites`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyTag.java",
            """
            public class MyTag {}
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyConsumer.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.common.Tag;

            @Component
            public class MyConsumer {
                public MyConsumer(@Tag(MyTag.class) MyService myService) {}
            }
            """.trimIndent(),
        )

        val sites = InjectionSiteSearch.findAllInjectionSites(project)
        val serviceSite = sites.find { it.requiredType.canonicalText == "MyService" }

        assertNotNull("Should find injection site for MyService", serviceSite)
        assertTrue(
            "Should extract MyTag from injection site",
            serviceSite!!.tagInfo.tagFqns.contains("MyTag"),
        )
    }

    fun `test no injection sites from plain class`() {
        configureAnnotations()

        myFixture.configureByText(
            "PlainClass.java",
            """
            public class PlainClass {
                public PlainClass(String someParam) {}
            }
            """.trimIndent(),
        )

        val sites = InjectionSiteSearch.findAllInjectionSites(project)
        val stringSites = sites.filter { it.requiredType.canonicalText == "java.lang.String" }

        assertTrue("Should not find injection sites from plain class", stringSites.isEmpty())
    }
}
