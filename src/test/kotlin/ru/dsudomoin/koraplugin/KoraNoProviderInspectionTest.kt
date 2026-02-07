package ru.dsudomoin.koraplugin

import com.intellij.codeInspection.InspectionManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getUastParentOfType
import ru.dsudomoin.koraplugin.inspection.KoraNoProviderInspection

class KoraNoProviderInspectionTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private val inspection = KoraNoProviderInspection()

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

    fun `test error on parameter with no provider`() {
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
                public MyController(MyService <error descr="No provider found for 'MyService'">myService</error>) {}
            }
            """.trimIndent(),
        )

        myFixture.checkHighlighting()
    }

    fun `test no error when provider exists`() {
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

        myFixture.checkHighlighting()
    }

    fun `test no error on plain class constructor`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "SomeType.java",
            """
            public class SomeType {}
            """.trimIndent(),
        )

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

    fun `test tag mismatch detected`() {
        configureAnnotations()

        myFixture.addFileToProject("MyTag.java", "public class MyTag {}")
        myFixture.addFileToProject("OtherTag.java", "public class OtherTag {}")
        myFixture.addFileToProject("MyService.java", "public interface MyService {}")
        myFixture.addFileToProject(
            "MyServiceImpl.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.common.Tag;

            @Component
            @Tag({OtherTag.class})
            public class MyServiceImpl implements MyService {
                public MyServiceImpl() {}
            }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyController.java",
            """
            import ru.tinkoff.kora.common.Component;
            import ru.tinkoff.kora.common.Tag;

            @Component
            public class MyController {
                public MyController(@Tag({MyTag.class}) MyService <caret>myService) {}
            }
            """.trimIndent(),
        )

        // Test via direct checkMethod call — checkHighlighting triggers AST loading restriction
        // for non-editor files when reading @Tag annotation values
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val uMethod = element.getUastParentOfType<UMethod>()
        assertNotNull("No UMethod found", uMethod)
        val manager = InspectionManager.getInstance(project)
        val problems = inspection.checkMethod(uMethod!!, manager, true)

        assertNotNull("Expected problems for tag mismatch", problems)
        assertEquals(1, problems!!.size)
        assertTrue(problems[0].descriptionTemplate.contains("No provider with matching tags"))
    }

    fun `test no error when All type is used`() {
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
                public MyController(All<MyService> services) {}
            }
            """.trimIndent(),
        )

        myFixture.checkHighlighting()
    }

    fun `test error on module factory method parameter with no provider`() {
        configureAnnotations()

        myFixture.addFileToProject("MyService.java", "public interface MyService {}")
        myFixture.addFileToProject("MyController.java", "public class MyController {}")

        myFixture.configureByText(
            "MyModule.java",
            """
            import ru.tinkoff.kora.common.Module;

            @Module
            public interface MyModule {
                default MyController createController(MyService <error descr="No provider found for 'MyService'">myService</error>) {
                    return new MyController();
                }
            }
            """.trimIndent(),
        )

        myFixture.checkHighlighting()
    }
}
