package io.github.dsudomoin.koraplugin

import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dsudomoin.koraplugin.query.KoraMacroCommand
import io.github.dsudomoin.koraplugin.query.KoraMacroFilterMode
import io.github.dsudomoin.koraplugin.query.KoraQueryMacro
import io.github.dsudomoin.koraplugin.query.KoraQueryMacroExpander

class KoraQueryMacroExpanderTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun configure() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/database/common/annotation/Table.java",
            "ru/tinkoff/kora/database/common/annotation/Column.java",
            "ru/tinkoff/kora/database/common/annotation/Id.java",
            "ru/tinkoff/kora/database/jdbc/EntityJdbc.java",
        )
        myFixture.addFileToProject(
            "Race.java",
            """
            import ru.tinkoff.kora.database.jdbc.EntityJdbc;
            import ru.tinkoff.kora.database.common.annotation.*;
            @EntityJdbc @Table("races")
            public record Race(@Id Long id, String raceName, @Column("slot_count") int slots) {}
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "RaceRepo.java",
            """
            import java.util.List;
            public interface RaceRepo {
                Race findById(Long id);
                List<Race> findAll();
                int insert(Race entity);
            }
            """.trimIndent()
        )
    }

    private fun method(name: String): PsiMethod {
        val repo = JavaPsiFacade.getInstance(project).findClass("RaceRepo", GlobalSearchScope.allScope(project))!!
        return repo.findMethodsByName(name, false).first()
    }

    private fun macro(
        target: String,
        command: KoraMacroCommand,
        mode: KoraMacroFilterMode = KoraMacroFilterMode.NONE,
        fields: List<String> = emptyList(),
    ) = KoraQueryMacro(target, command, mode, fields, "%{...}", TextRange(0, 1))

    fun testSelects() {
        configure()
        assertEquals(
            "id, race_name, slot_count",
            KoraQueryMacroExpander.expand(macro("return", KoraMacroCommand.SELECTS), method("findById"))
        )
    }

    fun testSelectsUnwrapsList() {
        configure()
        assertEquals(
            "id, race_name, slot_count",
            KoraQueryMacroExpander.expand(macro("return", KoraMacroCommand.SELECTS), method("findAll"))
        )
    }

    fun testTable() {
        configure()
        assertEquals(
            "races",
            KoraQueryMacroExpander.expand(macro("return", KoraMacroCommand.TABLE), method("findById"))
        )
    }

    fun testInsertsExcludeId() {
        configure()
        assertEquals(
            "races(race_name, slot_count) VALUES (:entity.raceName, :entity.slots)",
            KoraQueryMacroExpander.expand(
                macro("entity", KoraMacroCommand.INSERTS, KoraMacroFilterMode.EXCLUDE, listOf("@id")),
                method("insert")
            )
        )
    }

    fun testWhereById() {
        configure()
        assertEquals(
            "id = :entity.id",
            KoraQueryMacroExpander.expand(
                macro("entity", KoraMacroCommand.WHERE, KoraMacroFilterMode.INCLUDE, listOf("@id")),
                method("insert")
            )
        )
    }

    fun testUpdates() {
        configure()
        assertEquals(
            "id = :entity.id, race_name = :entity.raceName, slot_count = :entity.slots",
            KoraQueryMacroExpander.expand(macro("entity", KoraMacroCommand.UPDATES), method("insert"))
        )
    }

    fun testFallbackNullWhenUnresolved() {
        configure()
        assertNull(KoraQueryMacroExpander.expand(macro("nope", KoraMacroCommand.SELECTS), method("findById")))
    }

    fun testDescribeAlwaysNonEmpty() {
        assertTrue(KoraQueryMacroExpander.describe(macro("return", KoraMacroCommand.SELECTS)).isNotEmpty())
    }
}
