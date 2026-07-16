package io.github.dsudomoin.koraplugin

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dsudomoin.koraplugin.query.KoraEntityColumnResolver

class KoraEntityColumnResolverTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun stubs() = arrayOf(
        "ru/tinkoff/kora/database/common/annotation/Table.java",
        "ru/tinkoff/kora/database/common/annotation/Column.java",
        "ru/tinkoff/kora/database/common/annotation/Id.java",
        "ru/tinkoff/kora/database/jdbc/EntityJdbc.java",
    )

    fun testResolvesRecordColumns() {
        myFixture.configureByFiles(*stubs())
        myFixture.addFileToProject(
            "Race.java",
            """
            import ru.tinkoff.kora.database.jdbc.EntityJdbc;
            import ru.tinkoff.kora.database.common.annotation.*;
            @EntityJdbc @Table("races")
            public record Race(@Id Long id, String raceName, @Column("slot_count") int slots) {}
            """.trimIndent()
        )
        val cls = JavaPsiFacade.getInstance(project).findClass("Race", GlobalSearchScope.allScope(project))!!
        val info = KoraEntityColumnResolver.resolveEntity(cls)!!
        assertEquals("races", info.tableName)
        assertEquals(listOf("id", "race_name", "slot_count"), info.columns.map { it.columnName })
        assertTrue(info.columns[0].isId)
        assertFalse(info.columns[1].isId)
    }

    fun testDefaultTableSnakeCase() {
        myFixture.configureByFiles(*stubs())
        myFixture.addFileToProject(
            "UserAccount.java",
            """
            import ru.tinkoff.kora.database.jdbc.EntityJdbc;
            @EntityJdbc public record UserAccount(Long id) {}
            """.trimIndent()
        )
        val cls = JavaPsiFacade.getInstance(project).findClass("UserAccount", GlobalSearchScope.allScope(project))!!
        assertEquals("user_account", KoraEntityColumnResolver.resolveEntity(cls)!!.tableName)
    }
}
