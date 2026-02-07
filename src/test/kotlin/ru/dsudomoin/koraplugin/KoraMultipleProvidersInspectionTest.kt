package ru.dsudomoin.koraplugin

import com.intellij.codeInspection.InspectionManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElement
import ru.dsudomoin.koraplugin.inspection.KoraMultipleProvidersInspection

class KoraMultipleProvidersInspectionTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private val inspection = KoraMultipleProvidersInspection()

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(inspection)
    }

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

    fun `test warning on multiple providers`() {
        configureAnnotations()

        myFixture.addFileToProject("MyService.java", "public interface MyService {}")
        myFixture.addFileToProject(
            "MyServiceImplA.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyServiceImplA implements MyService {
                public MyServiceImplA() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "MyServiceImplB.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyServiceImplB implements MyService {
                public MyServiceImplB() {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyController.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyController {
                public MyController(MyService <warning descr="Multiple providers found for 'MyService': 2 candidates">myService</warning>) {}
            }
            """.trimIndent(),
        )

        myFixture.checkHighlighting()
    }

    fun `test no warning on single provider`() {
        configureAnnotations()

        myFixture.addFileToProject("MyService.java", "public interface MyService {}")
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

        myFixture.checkHighlighting()
    }

    fun `test no warning when All type is used`() {
        configureAnnotations()

        myFixture.addFileToProject("MyService.java", "public interface MyService {}")
        myFixture.addFileToProject(
            "MyServiceImplA.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyServiceImplA implements MyService {
                public MyServiceImplA() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "MyServiceImplB.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyServiceImplB implements MyService {
                public MyServiceImplB() {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyController.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.application.graph.All;

            @Component
            public class MyController {
                public MyController(All<MyService> services) {}
            }
            """.trimIndent(),
        )

        myFixture.checkHighlighting()
    }

    fun `test no warning when TagAny is used`() {
        configureAnnotations()

        myFixture.addFileToProject("MyService.java", "public interface MyService {}")
        myFixture.addFileToProject(
            "MyServiceImplA.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyServiceImplA implements MyService {
                public MyServiceImplA() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "MyServiceImplB.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyServiceImplB implements MyService {
                public MyServiceImplB() {}
            }
            """.trimIndent(),
        )

        // Test via direct checkMethod — @Tag.Any skips multiple providers warning
        myFixture.configureByText(
            "MyController.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.common.Tag;

            @Component
            public class MyController {
                public MyController(@Tag({Tag.Any.class}) MyService myService) {}
            }
            """.trimIndent(),
        )

        val psiMethod = PsiTreeUtil.findChildOfType(myFixture.file, PsiMethod::class.java)!!
        val uMethod = psiMethod.toUElement() as? UMethod
        assertNotNull("No UMethod found", uMethod)
        val manager = InspectionManager.getInstance(project)
        val problems = inspection.checkMethod(uMethod!!, manager, true)

        assertTrue("Expected no problems with @Tag.Any", problems == null || problems.isEmpty())
    }

    fun `test no warning on plain class`() {
        configureAnnotations()

        myFixture.addFileToProject("SomeType.java", "public class SomeType {}")

        myFixture.configureByText(
            "PlainClass.java",
            """
            public class PlainClass {
                public PlainClass(SomeType name) {}
            }
            """.trimIndent(),
        )

        myFixture.checkHighlighting()
    }
}
