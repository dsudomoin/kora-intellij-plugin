package ru.dsudomoin.koraplugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KoraLineMarkerProviderTest : BasePlatformTestCase() {

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

    fun `test gutter icon on Component constructor parameter`() {
        configureAnnotations()

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
                public MyController(MyService myService) {}
            }
            """.trimIndent(),
        )

        val gutters = myFixture.findAllGutters()
        val koraGutters = gutters.filter { it.tooltipText == "Navigate to Kora DI provider" }

        assertFalse("Expected at least one Kora gutter icon", koraGutters.isEmpty())
    }

    fun `test gutter icon on Module factory method parameter`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyRepository.java",
            """
            public interface MyRepository {}
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyRepositoryImpl.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyRepositoryImpl implements MyRepository {
                public MyRepositoryImpl() {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyModule.java",
            """
            import ru.tinkoff.kora.common.Module;

            @Module
            public interface MyModule {
                default MyService createService(MyRepository myRepo) {
                    return null;
                }
            }
            """.trimIndent(),
        )

        val gutters = myFixture.findAllGutters()
        val koraGutters = gutters.filter { it.tooltipText == "Navigate to Kora DI provider" }

        assertFalse("Expected at least one Kora gutter icon on Module parameter", koraGutters.isEmpty())
    }

    fun `test no gutter icon on plain class parameter`() {
        configureAnnotations()

        myFixture.configureByText(
            "PlainClass.java",
            """
            public class PlainClass {
                public PlainClass(String someParam) {}
            }
            """.trimIndent(),
        )

        val gutters = myFixture.findAllGutters()
        val koraGutters = gutters.filter { it.tooltipText == "Navigate to Kora DI provider" }

        assertTrue("Should not have Kora gutter icons on plain class", koraGutters.isEmpty())
    }
}
