package ru.dsudomoin.koraplugin.config.datasource

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.typesafe.config.*
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import ru.dsudomoin.koraplugin.util.KoraLibraryUtil

object KoraDataSourceExtractor {

    private val CONFIG_FILE_NAMES = listOf("application.conf", "application.yaml", "application.yml")

    private val DRIVER_PREFIXES = mapOf(
        "jdbc:postgresql://" to "postgresql",
        "jdbc:mysql://" to "mysql",
        "jdbc:mariadb://" to "mariadb",
        "jdbc:oracle:" to "oracle",
        "jdbc:sqlserver://" to "sqlserver",
        "jdbc:h2:" to "h2",
        "jdbc:clickhouse://" to "clickhouse",
        "jdbc:sqlite:" to "sqlite",
    )

    fun findDataSources(project: Project): List<KoraDataSourceDescriptor> {
        if (!KoraLibraryUtil.hasKoraLibrary(project)) return emptyList()

        val scope = GlobalSearchScope.projectScope(project)
        val result = mutableListOf<KoraDataSourceDescriptor>()

        for (fileName in CONFIG_FILE_NAMES) {
            val files = ReadAction.compute<Collection<VirtualFile>, Throwable> {
                FilenameIndex.getVirtualFilesByName(fileName, scope)
            }
            for (vFile in files) {
                when {
                    fileName.endsWith(".conf") -> {
                        val text = String(vFile.contentsToByteArray(), vFile.charset)
                        result.addAll(extractFromHocon(text, vFile))
                    }
                    fileName.endsWith(".yaml") || fileName.endsWith(".yml") -> {
                        val psiFile = ReadAction.compute<YAMLFile?, Throwable> {
                            PsiManager.getInstance(project).findFile(vFile) as? YAMLFile
                        }
                        if (psiFile != null) {
                            result.addAll(ReadAction.compute<List<KoraDataSourceDescriptor>, Throwable> {
                                extractFromYaml(psiFile, vFile)
                            })
                        }
                    }
                }
            }
        }
        return result
    }

    fun extractFromHocon(text: String, sourceFile: VirtualFile): List<KoraDataSourceDescriptor> {
        val config = try {
            val parseOptions = ConfigParseOptions.defaults()
                .setSyntax(ConfigSyntax.CONF)
            val parsed = ConfigFactory.parseString(text, parseOptions)
            parsed.resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
        } catch (_: ConfigException) {
            return emptyList()
        }
        return findJdbcUrlInConfig(config.root(), "", sourceFile)
    }

    fun extractFromYaml(yamlFile: YAMLFile, sourceFile: VirtualFile): List<KoraDataSourceDescriptor> {
        val topMapping = yamlFile.documents.firstOrNull()?.topLevelValue as? YAMLMapping
            ?: return emptyList()
        return findJdbcUrlInYaml(topMapping, "", sourceFile)
    }

    fun detectDriverType(jdbcUrl: String): String? {
        for ((prefix, driver) in DRIVER_PREFIXES) {
            if (jdbcUrl.startsWith(prefix)) return driver
        }
        return null
    }

    private fun findJdbcUrlInConfig(
        obj: ConfigObject,
        prefix: String,
        sourceFile: VirtualFile,
    ): List<KoraDataSourceDescriptor> {
        val result = mutableListOf<KoraDataSourceDescriptor>()

        if (obj.containsKey("jdbcUrl")) {
            val jdbcUrl = try {
                obj.toConfig().getString("jdbcUrl")
            } catch (_: ConfigException) {
                null
            }
            if (jdbcUrl != null) {
                val config = obj.toConfig()
                val name = getStringOrNull(config, "poolName")
                    ?: prefix.substringAfterLast('.').ifEmpty { "datasource" }
                result.add(
                    KoraDataSourceDescriptor(
                        name = name,
                        jdbcUrl = jdbcUrl,
                        username = getStringOrNull(config, "username"),
                        password = getStringOrNull(config, "password"),
                        configPath = prefix,
                        sourceFile = sourceFile,
                        driverType = detectDriverType(jdbcUrl),
                    )
                )
            }
        }

        for ((key, value) in obj) {
            if (value is ConfigObject) {
                val childPrefix = if (prefix.isEmpty()) key else "$prefix.$key"
                result.addAll(findJdbcUrlInConfig(value, childPrefix, sourceFile))
            }
        }
        return result
    }

    private fun findJdbcUrlInYaml(
        mapping: YAMLMapping,
        prefix: String,
        sourceFile: VirtualFile,
    ): List<KoraDataSourceDescriptor> {
        val result = mutableListOf<KoraDataSourceDescriptor>()

        val jdbcUrlKv = mapping.getKeyValueByKey("jdbcUrl")
            ?: mapping.getKeyValueByKey("jdbc-url")
        if (jdbcUrlKv != null) {
            val jdbcUrl = jdbcUrlKv.valueText
            if (jdbcUrl.isNotBlank()) {
                val name = getYamlValue(mapping, "poolName", "pool-name")
                    ?: prefix.substringAfterLast('.').ifEmpty { "datasource" }
                result.add(
                    KoraDataSourceDescriptor(
                        name = name,
                        jdbcUrl = jdbcUrl,
                        username = getYamlValue(mapping, "username"),
                        password = getYamlValue(mapping, "password"),
                        configPath = prefix,
                        sourceFile = sourceFile,
                        driverType = detectDriverType(jdbcUrl),
                    )
                )
            }
        }

        for (kv in mapping.keyValues) {
            val childMapping = kv.value as? YAMLMapping ?: continue
            val childPrefix = if (prefix.isEmpty()) kv.keyText else "$prefix.${kv.keyText}"
            result.addAll(findJdbcUrlInYaml(childMapping, childPrefix, sourceFile))
        }
        return result
    }

    private fun getStringOrNull(config: Config, key: String): String? {
        return try {
            config.getString(key)
        } catch (_: ConfigException) {
            null
        }
    }

    private fun getYamlValue(mapping: YAMLMapping, vararg keys: String): String? {
        for (key in keys) {
            val kv = mapping.getKeyValueByKey(key)
            if (kv != null && kv.valueText.isNotBlank()) return kv.valueText
        }
        return null
    }
}
