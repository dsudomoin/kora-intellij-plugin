package ru.dsudomoin.koraplugin

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.dsudomoin.koraplugin.config.datasource.KoraDataSourceExtractor

class KoraDataSourceExtractorTest : BasePlatformTestCase() {

    private fun mockVirtualFile(): VirtualFile {
        val tempFile = myFixture.addFileToProject("application.conf", "")
        return tempFile.virtualFile
    }

    fun `test extract single datasource from HOCON`() {
        val hocon = """
            postgres {
              jdbcUrl = "jdbc:postgresql://localhost:5432/mineflux"
              username = "postgres"
              password = "postgres"
              poolName = "flux_commons_pool"
              initializationFailTimeout = 1s
            }
        """.trimIndent()

        val result = KoraDataSourceExtractor.extractFromHocon(hocon, mockVirtualFile())
        assertEquals(1, result.size)

        val ds = result[0]
        assertEquals("flux_commons_pool", ds.name)
        assertEquals("jdbc:postgresql://localhost:5432/mineflux", ds.jdbcUrl)
        assertEquals("postgres", ds.username)
        assertEquals("postgres", ds.password)
        assertEquals("postgres", ds.configPath)
        assertEquals("postgresql", ds.driverType)
    }

    fun `test extract multiple datasources from HOCON`() {
        val hocon = """
            db {
              primary {
                jdbcUrl = "jdbc:postgresql://localhost:5432/primary"
                username = "user1"
                password = "pass1"
              }
              secondary {
                jdbcUrl = "jdbc:mysql://localhost:3306/secondary"
                username = "user2"
                password = "pass2"
              }
            }
        """.trimIndent()

        val result = KoraDataSourceExtractor.extractFromHocon(hocon, mockVirtualFile())
        assertEquals(2, result.size)

        val primary = result.find { it.configPath == "db.primary" }!!
        assertEquals("primary", primary.name)
        assertEquals("jdbc:postgresql://localhost:5432/primary", primary.jdbcUrl)
        assertEquals("postgresql", primary.driverType)

        val secondary = result.find { it.configPath == "db.secondary" }!!
        assertEquals("secondary", secondary.name)
        assertEquals("jdbc:mysql://localhost:3306/secondary", secondary.jdbcUrl)
        assertEquals("mysql", secondary.driverType)
    }

    fun `test no datasource without jdbcUrl`() {
        val hocon = """
            redis {
              host = "localhost"
              port = 6379
            }
        """.trimIndent()

        val result = KoraDataSourceExtractor.extractFromHocon(hocon, mockVirtualFile())
        assertEquals(0, result.size)
    }

    fun `test deeply nested jdbcUrl`() {
        val hocon = """
            services {
              billing {
                database {
                  jdbcUrl = "jdbc:h2:mem:billing"
                  username = "sa"
                }
              }
            }
        """.trimIndent()

        val result = KoraDataSourceExtractor.extractFromHocon(hocon, mockVirtualFile())
        assertEquals(1, result.size)

        val ds = result[0]
        assertEquals("database", ds.name)
        assertEquals("jdbc:h2:mem:billing", ds.jdbcUrl)
        assertEquals("services.billing.database", ds.configPath)
        assertEquals("h2", ds.driverType)
    }

    fun `test name fallback to section key when poolName absent`() {
        val hocon = """
            mydb {
              jdbcUrl = "jdbc:postgresql://localhost:5432/test"
            }
        """.trimIndent()

        val result = KoraDataSourceExtractor.extractFromHocon(hocon, mockVirtualFile())
        assertEquals(1, result.size)
        assertEquals("mydb", result[0].name)
        assertNull(result[0].username)
        assertNull(result[0].password)
    }

    fun `test detect driver types`() {
        assertEquals("postgresql", KoraDataSourceExtractor.detectDriverType("jdbc:postgresql://localhost/db"))
        assertEquals("mysql", KoraDataSourceExtractor.detectDriverType("jdbc:mysql://localhost/db"))
        assertEquals("mariadb", KoraDataSourceExtractor.detectDriverType("jdbc:mariadb://localhost/db"))
        assertEquals("h2", KoraDataSourceExtractor.detectDriverType("jdbc:h2:mem:test"))
        assertEquals("oracle", KoraDataSourceExtractor.detectDriverType("jdbc:oracle:thin:@localhost:1521:orcl"))
        assertEquals("clickhouse", KoraDataSourceExtractor.detectDriverType("jdbc:clickhouse://localhost/db"))
        assertEquals("sqlite", KoraDataSourceExtractor.detectDriverType("jdbc:sqlite:test.db"))
        assertNull(KoraDataSourceExtractor.detectDriverType("jdbc:unknown://localhost"))
    }

    fun `test invalid HOCON returns empty`() {
        val result = KoraDataSourceExtractor.extractFromHocon("{{invalid", mockVirtualFile())
        assertEquals(0, result.size)
    }

    fun `test HOCON with unresolved substitutions`() {
        val hocon = """
            postgres {
              jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
              username = ${"$"}{?DB_USER}
              password = ${"$"}{?DB_PASS}
            }
        """.trimIndent()

        val result = KoraDataSourceExtractor.extractFromHocon(hocon, mockVirtualFile())
        assertEquals(1, result.size)
        assertEquals("jdbc:postgresql://localhost:5432/mydb", result[0].jdbcUrl)
    }
}
