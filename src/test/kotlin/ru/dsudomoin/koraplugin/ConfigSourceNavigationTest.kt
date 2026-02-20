package ru.dsudomoin.koraplugin

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.dsudomoin.koraplugin.config.ConfigPathResolver
import ru.dsudomoin.koraplugin.config.ConfigSourceSearch

class ConfigSourceNavigationTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun configureAnnotations() {
        myFixture.configureByFiles(
            "ru/tinkoff/kora/config/common/annotation/ConfigSource.java",
        )
    }

    private fun findClass(fqn: String) =
        JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))!!

    fun `test ConfigSourceSearch finds annotated class`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "HubConfig.java",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource;

            @ConfigSource("hub")
            public interface HubConfig {
                String name();
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val entries = ConfigSourceSearch.findAllConfigSources(project)
        assertEquals(1, entries.size)
        assertEquals("hub", entries[0].path)
        assertEquals("HubConfig", entries[0].psiClass.name)
    }

    fun `test resolveConfigKeyToMethod resolves root path to class`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "HubConfig.java",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource;

            @ConfigSource("hub")
            public interface HubConfig {
                String name();
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val target = ConfigPathResolver.resolveConfigKeyToMethod(project, "hub")
        assertNotNull("Should resolve hub to HubConfig class", target)
        assertTrue("Expected PsiClass", target is PsiClass)
        assertEquals("HubConfig", (target as PsiClass).name)
    }

    fun `test resolveConfigKeyToMethod resolves simple path`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "HubConfig.java",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource;

            @ConfigSource("hub")
            public interface HubConfig {
                String name();
                Spawn spawn();
                interface Spawn {
                    String location();
                }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val target = ConfigPathResolver.resolveConfigKeyToMethod(project, "hub.name")
        assertNotNull("Should resolve hub.name", target)
        assertTrue("Expected PsiMethod", target is PsiMethod)
        assertEquals("name", (target as PsiMethod).name)
    }

    fun `test resolveConfigKeyToMethod resolves nested path`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "HubConfig.java",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource;

            @ConfigSource("hub")
            public interface HubConfig {
                Spawn spawn();
                interface Spawn {
                    String location();
                }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val spawn = ConfigPathResolver.resolveConfigKeyToMethod(project, "hub.spawn")
        assertNotNull("Should resolve hub.spawn", spawn)
        assertTrue("Expected PsiMethod", spawn is PsiMethod)
        assertEquals("spawn", (spawn as PsiMethod).name)

        val location = ConfigPathResolver.resolveConfigKeyToMethod(project, "hub.spawn.location")
        assertNotNull("Should resolve hub.spawn.location", location)
        assertTrue("Expected PsiMethod", location is PsiMethod)
        assertEquals("location", (location as PsiMethod).name)
    }

    fun `test resolveConfigKeyToMethod with dotted ConfigSource path`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "AuthConfig.java",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource;

            @ConfigSource("auth.session")
            public interface AuthConfig {
                int timeToAuth();
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val target = ConfigPathResolver.resolveConfigKeyToMethod(project, "auth.session.timeToAuth")
        assertNotNull("Should resolve auth.session.timeToAuth", target)
        assertTrue("Expected PsiMethod", target is PsiMethod)
        assertEquals("timeToAuth", (target as PsiMethod).name)
    }

    fun `test resolveConfigKeyToMethod returns null for unknown path`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "HubConfig.java",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource;

            @ConfigSource("hub")
            public interface HubConfig {
                String name();
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        assertNull(ConfigPathResolver.resolveConfigKeyToMethod(project, "unknown.path"))
        assertNull(ConfigPathResolver.resolveConfigKeyToMethod(project, "hub.nonexistent"))
    }

    fun `test resolveMethodToConfigPath returns full path`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "HubConfig.java",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource;

            @ConfigSource("hub")
            public interface HubConfig {
                Spawn spawn();
                interface Spawn {
                    String location();
                }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val hubConfig = findClass("HubConfig")
        val spawnMethod = hubConfig.findMethodsByName("spawn", false).first()

        val path = ConfigPathResolver.resolveMethodToConfigPath(spawnMethod)
        assertEquals("hub.spawn", path)
    }

    fun `test resolveMethodToConfigPath for nested interface method`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "HubConfig.java",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource;

            @ConfigSource("hub")
            public interface HubConfig {
                Spawn spawn();
                interface Spawn {
                    String location();
                }
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val hubConfig = findClass("HubConfig")
        val spawnClass = hubConfig.innerClasses.first { it.name == "Spawn" }
        val locationMethod = spawnClass.findMethodsByName("location", false).first()

        val path = ConfigPathResolver.resolveMethodToConfigPath(locationMethod)
        assertEquals("hub.spawn.location", path)
    }

    fun `test resolveMethodToConfigPath with dotted ConfigSource path`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "AuthConfig.java",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource;

            @ConfigSource("auth.session")
            public interface AuthConfig {
                int timeToAuth();
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val authConfig = findClass("AuthConfig")
        val method = authConfig.findMethodsByName("timeToAuth", false).first()

        val path = ConfigPathResolver.resolveMethodToConfigPath(method)
        assertEquals("auth.session.timeToAuth", path)
    }

    // --- Kotlin interface tests ---

    fun `test resolveConfigKeyToMethod with Kotlin interface fun methods`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "HubConfig.kt",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource

            @ConfigSource("hub")
            interface HubConfig {
                fun spawn(): Spawn
                fun protection(): Protection
            }

            interface Spawn {
                fun location(): String
            }

            interface Protection {
                fun enabled(): Boolean
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val entries = ConfigSourceSearch.findAllConfigSources(project)
        assertEquals("Should find Kotlin @ConfigSource interface", 1, entries.size)
        assertEquals("hub", entries[0].path)

        val spawn = ConfigPathResolver.resolveConfigKeyToMethod(project, "hub.spawn")
        assertNotNull("Should resolve hub.spawn in Kotlin interface", spawn)
        assertTrue("Expected PsiMethod", spawn is PsiMethod)

        val location = ConfigPathResolver.resolveConfigKeyToMethod(project, "hub.spawn.location")
        assertNotNull("Should resolve hub.spawn.location through Kotlin interface", location)
        assertTrue("Expected PsiMethod", location is PsiMethod)
    }

    fun `test resolveConfigKeyToMethod with Kotlin interface val properties`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "AppConfig.kt",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource

            @ConfigSource("app")
            interface AppConfig {
                val serverName: String
                val maxRetries: Int
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val serverName = ConfigPathResolver.resolveConfigKeyToMethod(project, "app.serverName")
        assertNotNull("Should resolve app.serverName (Kotlin val getter)", serverName)

        val maxRetries = ConfigPathResolver.resolveConfigKeyToMethod(project, "app.maxRetries")
        assertNotNull("Should resolve app.maxRetries (Kotlin val getter)", maxRetries)
    }

    fun `test resolveConfigKeyToMethod with kebab-case on Kotlin interface`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "ServerConfig.kt",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource

            @ConfigSource("server")
            interface ServerConfig {
                fun maxConnections(): Int
                val readTimeout: Long
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val funKebab = ConfigPathResolver.resolveConfigKeyToMethod(project, "server.max-connections")
        assertNotNull("Should resolve kebab-case for fun method", funKebab)

        val valKebab = ConfigPathResolver.resolveConfigKeyToMethod(project, "server.read-timeout")
        assertNotNull("Should resolve kebab-case for val property", valKebab)
    }

    fun `test resolveMethodToConfigPath for Kotlin val getter`() {
        configureAnnotations()

        myFixture.addFileToProject(
            "AppConfig.kt",
            """
            import ru.tinkoff.kora.config.common.annotation.ConfigSource

            @ConfigSource("app")
            interface AppConfig {
                val serverName: String
            }
            """.trimIndent(),
        )

        myFixture.configureByText("Dummy.java", "class Dummy {}")

        val appConfig = findClass("AppConfig")
        // Kotlin val → getter method getServerName()
        val getter = appConfig.findMethodsByName("getServerName", false).firstOrNull()
            ?: appConfig.findMethodsByName("serverName", false).firstOrNull()
        assertNotNull("Should find getter method for val property", getter)

        val path = ConfigPathResolver.resolveMethodToConfigPath(getter!!)
        assertEquals("app.serverName", path)
    }
}
