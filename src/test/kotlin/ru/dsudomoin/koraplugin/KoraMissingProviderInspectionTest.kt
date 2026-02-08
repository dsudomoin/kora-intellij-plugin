package ru.dsudomoin.koraplugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.dsudomoin.koraplugin.inspection.KoraMissingProviderInspection

class KoraMissingProviderInspectionTest : BasePlatformTestCase() {

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

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KoraMissingProviderInspection())
    }

    fun `test no warning when provider exists`() {
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

        myFixture.checkHighlighting(true, false, true)
    }

    fun `test warning when no provider exists`() {
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
                public MyController(MyService <weak_warning descr="No Kora DI provider found for type 'MyService'">myService</weak_warning>) {}
            }
            """.trimIndent(),
        )

        myFixture.checkHighlighting(true, false, true)
    }

    fun `test no warning for non-Kora class`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.configureByText(
            "PlainClass.java",
            """
            public class PlainClass {
                public PlainClass(MyService someParam) {}
            }
            """.trimIndent(),
        )

        myFixture.checkHighlighting(true, false, true)
    }

    fun `test no warning for All type without provider`() {
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
            import ru.tinkoff.kora.application.graph.All;

            @Component
            public class MyController {
                public MyController(All<MyService> allServices) {}
            }
            """.trimIndent(),
        )

        myFixture.checkHighlighting(true, false, true)
    }

    fun `test warning in Module factory method parameter`() {
        configureAnnotations()

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

        myFixture.configureByText(
            "MyModule.java",
            """
            import ru.tinkoff.kora.common.Module;

            @Module
            public interface MyModule {
                default MyService createService(MyRepository <weak_warning descr="No Kora DI provider found for type 'MyRepository'">myRepo</weak_warning>) {
                    return null;
                }
            }
            """.trimIndent(),
        )

        myFixture.checkHighlighting(true, false, true)
    }

    fun `test warning for Kotlin Component class with primary constructor`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.kt",
            """
            interface MyService
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyController.kt",
            """
            import ru.tinkoff.kora.common.Component

            @Component
            class MyController(
                private val <weak_warning descr="No Kora DI provider found for type 'MyService'">myService</weak_warning>: MyService
            )
            """.trimIndent(),
        )

        myFixture.checkHighlighting(true, false, true)
    }

    fun `test no warning for Kotlin Component class when provider exists`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.kt",
            """
            interface MyService
            """.trimIndent(),
        )

        myFixture.addFileToProject(
            "MyServiceImpl.kt",
            """
            import ru.tinkoff.kora.common.Component

            @Component
            class MyServiceImpl : MyService
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyController.kt",
            """
            import ru.tinkoff.kora.common.Component

            @Component
            class MyController(
                private val myService: MyService
            )
            """.trimIndent(),
        )

        myFixture.checkHighlighting(true, false, true)
    }

    fun `test warning only on parameter without provider`() {
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

        myFixture.addFileToProject(
            "MyRepository.java",
            """
            public interface MyRepository {}
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyController.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyController {
                public MyController(MyService myService, MyRepository <weak_warning descr="No Kora DI provider found for type 'MyRepository'">myRepo</weak_warning>) {}
            }
            """.trimIndent(),
        )

        myFixture.checkHighlighting(true, false, true)
    }
}
