package io.github.dsudomoin.koraplugin

import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dsudomoin.koraplugin.query.KoraQuerySupport

class KoraQuerySupportTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testGetQueryMethodFromLiteral() {
        myFixture.configureByFiles("ru/tinkoff/kora/database/common/annotation/Query.java")
        val file = myFixture.configureByText(
            "Repo.java",
            """
            import ru.tinkoff.kora.database.common.annotation.Query;
            public interface Repo {
                @Query("SELECT 1")
                int ping();
            }
            """.trimIndent()
        )
        val literal = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression::class.java)
            .first { it.value == "SELECT 1" }
        val method = KoraQuerySupport.getQueryMethod(literal)
        assertNotNull(method)
        assertEquals("ping", method!!.name)
    }

    fun testNonQueryLiteralReturnsNull() {
        val file = myFixture.configureByText(
            "Plain.java",
            """
            public class Plain { String s = "SELECT 1"; }
            """.trimIndent()
        )
        val literal = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression::class.java)
            .first { it.value == "SELECT 1" }
        assertNull(KoraQuerySupport.getQueryMethod(literal))
    }
}
