package ru.dsudomoin.koraplugin

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
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

    private fun collectMarkers(elements: List<PsiElement>): List<LineMarkerInfo<*>> {
        val result = mutableListOf<LineMarkerInfo<*>>()
        provider.collectSlowLineMarkers(elements.toMutableList(), result)
        return result
    }

    private fun findParameterIdentifiers(): List<PsiIdentifier> {
        return PsiTreeUtil.findChildrenOfType(myFixture.file, PsiIdentifier::class.java)
            .filter { it.parent is PsiParameter }
    }

    private fun findClassNameIdentifiers(): List<PsiIdentifier> {
        return PsiTreeUtil.findChildrenOfType(myFixture.file, PsiIdentifier::class.java)
            .filter { it.parent is PsiClass }
    }

    private fun findMethodNameIdentifiers(): List<PsiIdentifier> {
        return PsiTreeUtil.findChildrenOfType(myFixture.file, PsiIdentifier::class.java)
            .filter { it.parent is PsiMethod && !(it.parent as PsiMethod).isConstructor }
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

        val markers = collectMarkers(paramIdents)
        assertFalse("Expected at least one Kora gutter icon", markers.isEmpty())
        assertEquals(KoraLineMarkerProvider.INJECTION_TOOLTIP, markers.first().lineMarkerTooltip)
    }

    fun `test gutter icon on Module factory method with parameter on same line`() {
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

        // Parameter on same line as factory method → no separate param marker
        val paramIdents = findParameterIdentifiers()
        assertFalse("Should find parameter identifiers", paramIdents.isEmpty())
        val paramMarkers = collectMarkers(paramIdents)
        assertTrue("Param marker should be suppressed (handled by factory method marker)", paramMarkers.isEmpty())

        // Factory method name gets combined marker instead
        val methodIdents = findMethodNameIdentifiers()
        assertFalse("Should find method name identifiers", methodIdents.isEmpty())
        val methodMarkers = collectMarkers(methodIdents)
        assertFalse("Expected combined gutter icon on factory method name", methodMarkers.isEmpty())
        assertEquals(KoraLineMarkerProvider.TOOLTIP_TEXT, methodMarkers.first().lineMarkerTooltip)
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

        val markers = collectMarkers(paramIdents)
        assertTrue("Should not have Kora gutter icons on plain class", markers.isEmpty())
    }

    fun `test gutter icon on Component class name`() {
        configureAnnotations()

        myFixture.configureByText(
            "MyServiceImpl.java",
            """
            import ru.tinkoff.kora.common.Component;

            @Component
            public class MyServiceImpl {
                public MyServiceImpl() {}
            }
            """.trimIndent(),
        )

        val classIdents = findClassNameIdentifiers()
        assertFalse("Should find class name identifiers", classIdents.isEmpty())

        val markers = collectMarkers(classIdents)
        assertFalse("Expected gutter icon on @Component class name", markers.isEmpty())
        assertEquals(KoraLineMarkerProvider.PROVIDER_TOOLTIP, markers.first().lineMarkerTooltip)
    }

    fun `test gutter icon on Module factory method name`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "MyService.java",
            """
            public interface MyService {}
            """.trimIndent(),
        )

        myFixture.configureByText(
            "MyModule.java",
            """
            import ru.tinkoff.kora.common.Module;

            @Module
            public interface MyModule {
                default MyService createService() {
                    return null;
                }
            }
            """.trimIndent(),
        )

        val methodIdents = findMethodNameIdentifiers()
        assertFalse("Should find method name identifiers", methodIdents.isEmpty())

        val markers = collectMarkers(methodIdents)
        assertFalse("Expected gutter icon on factory method name", markers.isEmpty())
        assertEquals(KoraLineMarkerProvider.PROVIDER_TOOLTIP, markers.first().lineMarkerTooltip)
    }

    fun `test no gutter icon on void method in module`() {
        configureAnnotations()

        myFixture.configureByText(
            "MyModule.java",
            """
            import ru.tinkoff.kora.common.Module;

            @Module
            public interface MyModule {
                default void doNothing() {}
            }
            """.trimIndent(),
        )

        val methodIdents = findMethodNameIdentifiers()
        assertFalse("Should find method name identifiers", methodIdents.isEmpty())

        val markers = collectMarkers(methodIdents)
        assertTrue("Should not have Kora gutter icon on void method", markers.isEmpty())
    }

    fun `test no gutter icon on non-provider class`() {
        configureAnnotations()

        myFixture.configureByText(
            "PlainClass.java",
            """
            public class PlainClass {
                public String getName() { return ""; }
            }
            """.trimIndent(),
        )

        val classIdents = findClassNameIdentifiers()
        val methodIdents = findMethodNameIdentifiers()

        val classMarkers = collectMarkers(classIdents)
        val methodMarkers = collectMarkers(methodIdents)

        assertTrue("Should not have Kora gutter icon on non-provider class", classMarkers.isEmpty())
        assertTrue("Should not have Kora gutter icon on non-provider method", methodMarkers.isEmpty())
    }
}
