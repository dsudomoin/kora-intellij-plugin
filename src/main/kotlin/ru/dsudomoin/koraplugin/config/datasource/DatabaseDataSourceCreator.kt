package ru.dsudomoin.koraplugin.config.datasource

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

object DatabaseDataSourceCreator {

    private val LOG = Logger.getInstance(DatabaseDataSourceCreator::class.java)

    fun isAvailable(): Boolean {
        return try {
            Class.forName("com.intellij.database.dataSource.LocalDataSource")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    fun createDataSources(project: Project, descriptors: List<KoraDataSourceDescriptor>): Int {
        try {
            return doCreate(project, descriptors)
        } catch (e: Exception) {
            LOG.warn("Failed to create data sources via Database plugin API", e)
            return 0
        }
    }

    private fun doCreate(project: Project, descriptors: List<KoraDataSourceDescriptor>): Int {
        val localDataSourceClass = Class.forName("com.intellij.database.dataSource.LocalDataSource")
        val dataSourceManagerClass = Class.forName("com.intellij.database.dataSource.DataSourceManager")
        val databaseDriverManagerClass = Class.forName("com.intellij.database.dataSource.DatabaseDriverManager")

        // DataSourceManager.getManager(project)
        val getManagerMethod = dataSourceManagerClass.getMethod("getManager", Project::class.java)
        val manager = getManagerMethod.invoke(null, project)

        // DatabaseDriverManager.getInstance()
        val getInstanceMethod = databaseDriverManagerClass.getMethod("getInstance")
        val driverManager = getInstanceMethod.invoke(null)

        // manager.getDataSources() — for duplicate check
        val getDataSourcesMethod = manager.javaClass.getMethod("getDataSources")
        @Suppress("UNCHECKED_CAST")
        val existingDataSources = getDataSourcesMethod.invoke(manager) as List<Any>
        val existingUrls = existingDataSources.mapNotNull { ds ->
            try {
                ds.javaClass.getMethod("getUrl").invoke(ds) as? String
            } catch (_: Exception) {
                null
            }
        }.toSet()

        val addDataSourceMethod = manager.javaClass.methods.find {
            it.name == "addDataSource" && it.parameterCount == 1
        } ?: throw IllegalStateException("addDataSource method not found on DataSourceManager")

        var created = 0
        for (desc in descriptors) {
            if (desc.jdbcUrl in existingUrls) {
                LOG.info("Skipping duplicate data source: ${desc.name} (${desc.jdbcUrl})")
                continue
            }

            val ds = localDataSourceClass.getDeclaredConstructor().newInstance()

            // ds.name = desc.name
            localDataSourceClass.getMethod("setName", String::class.java).invoke(ds, desc.name)

            // ds.url = desc.jdbcUrl
            localDataSourceClass.getMethod("setUrl", String::class.java).invoke(ds, desc.jdbcUrl)

            // ds.username = desc.username
            if (desc.username != null) {
                try {
                    localDataSourceClass.getMethod("setUsername", String::class.java)
                        .invoke(ds, desc.username)
                } catch (_: NoSuchMethodException) {
                    // API may differ between versions
                }
            }

            // Set driver if detected
            if (desc.driverType != null) {
                try {
                    val getDriverMethod = driverManager.javaClass.methods.find {
                        it.name == "getDriver" && it.parameterCount == 1
                            && it.parameterTypes[0] == String::class.java
                    }
                    if (getDriverMethod != null) {
                        val driver = getDriverMethod.invoke(driverManager, desc.driverType)
                        if (driver != null) {
                            localDataSourceClass.getMethod("setDatabaseDriver", driver.javaClass.interfaces.firstOrNull()
                                ?: driver.javaClass)
                                .invoke(ds, driver)
                        }
                    }
                } catch (e: Exception) {
                    LOG.info("Could not set driver for ${desc.driverType}: ${e.message}")
                }
            }

            addDataSourceMethod.invoke(manager, ds)
            created++
            LOG.info("Created data source: ${desc.name} -> ${desc.jdbcUrl}")
        }
        return created
    }
}
