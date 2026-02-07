package ru.dsudomoin.koraplugin

import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.dsudomoin.koraplugin.navigation.KoraLineMarkerProvider

class KoraLineMarkerProviderTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private val provider = KoraLineMarkerProvider()

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

    private fun findParameterIdentifiers(): List<PsiIdentifier> {
        return PsiTreeUtil.findChildrenOfType(myFixture.file, PsiIdentifier::class.java)
            .filter { it.parent is PsiParameter }
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

        val paramIdents = findParameterIdentifiers()
        assertFalse("Should find parameter identifiers", paramIdents.isEmpty())

        val markers = paramIdents.mapNotNull { provider.getLineMarkerInfo(it) }
        assertFalse("Expected at least one Kora gutter icon", markers.isEmpty())
        assertEquals("Navigate to Kora DI provider", markers.first().lineMarkerTooltip)
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

        val paramIdents = findParameterIdentifiers()
        assertFalse("Should find parameter identifiers", paramIdents.isEmpty())

        val markers = paramIdents.mapNotNull { provider.getLineMarkerInfo(it) }
        assertFalse("Expected at least one Kora gutter icon on Module parameter", markers.isEmpty())
        assertEquals("Navigate to Kora DI provider", markers.first().lineMarkerTooltip)
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

        val paramIdents = findParameterIdentifiers()
        assertFalse("Should find parameter identifiers", paramIdents.isEmpty())

        val markers = paramIdents.mapNotNull { provider.getLineMarkerInfo(it) }
        assertTrue("Should not have Kora gutter icons on plain class", markers.isEmpty())
    }
}
