package io.github.dsudomoin.koraplugin.endpoints

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DbEntityCollectorTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun configureTableAnnotation() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/common/Component.java",
            "ru/tinkoff/kora/database/common/annotation/Table.java",
            "ru/tinkoff/kora/database/jdbc/EntityJdbc.java",
        )
    }

    fun `test collects table entities from java classes`() {
        configureTableAnnotation()

        myFixture.addFileToProject(
            "User.java",
            """
            import ru.tinkoff.kora.database.common.annotation.Table;

            @Table("users")
            public record User(Long id, String name) {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "Order.java",
            """
            import ru.tinkoff.kora.database.common.annotation.Table;

            @Table("orders")
            public class Order {
                private final Long id;
                public Order(Long id) { this.id = id; }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val entities = DbEntityCollector.collect(project)
        assertEquals(listOf("orders", "users"), entities.map { it.tableName })
        assertEquals("User", entities.first { it.tableName == "users" }.classFqn)
        assertNull(entities.first { it.tableName == "users" }.target.methodName)
    }

    fun `test collects table entities from kotlin classes`() {
        configureTableAnnotation()

        myFixture.configureByText(
            "KtUser.kt",
            """
            import ru.tinkoff.kora.database.common.annotation.Table

            @Table("kt_users")
            data class KtUser(val id: Long, val name: String)
            """.trimIndent(),
        )
        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val entities = DbEntityCollector.collect(project)
        assertEquals(1, entities.size)
        assertEquals("kt_users", entities.single().tableName)
        assertEquals("KtUser", entities.single().classFqn)
    }

    fun `test collects entity jdbc classes without table annotation`() {
        configureTableAnnotation()

        myFixture.addFileToProject(
            "Person.java",
            """
            import ru.tinkoff.kora.database.jdbc.EntityJdbc;

            @EntityJdbc
            public record Person(Long id) {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "Pet.kt",
            """
            import ru.tinkoff.kora.database.jdbc.EntityJdbc

            @EntityJdbc
            data class Pet(val id: Long)
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val entities = DbEntityCollector.collect(project)
        assertEquals(listOf("Person", "Pet"), entities.map { it.tableName })
        assertEquals("Person", entities.first { it.tableName == "Person" }.classFqn)
    }

    fun `test entity with both annotations appears once with table name`() {
        configureTableAnnotation()

        myFixture.addFileToProject(
            "User.java",
            """
            import ru.tinkoff.kora.database.common.annotation.Table;
            import ru.tinkoff.kora.database.jdbc.EntityJdbc;

            @EntityJdbc
            @Table("users")
            public record User(Long id) {}
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val entities = DbEntityCollector.collect(project)
        assertEquals(1, entities.size)
        assertEquals("users", entities.single().tableName)
        assertEquals("User", entities.single().classFqn)
    }

    fun `test returns empty when no table entities exist`() {
        configureTableAnnotation()

        myFixture.configureByText(
            "Plain.java",
            """
            public class Plain {
                private final Long id;
                public Plain(Long id) { this.id = id; }
            }
            """.trimIndent(),
        )

        assertTrue(DbEntityCollector.collect(project).isEmpty())
    }
}
