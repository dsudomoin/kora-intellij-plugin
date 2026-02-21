package ru.dsudomoin.koraplugin.config.datasource

import com.intellij.openapi.vfs.VirtualFile

data class KoraDataSourceDescriptor(
    val name: String,
    val jdbcUrl: String,
    val username: String?,
    val password: String?,
    val configPath: String,
    val sourceFile: VirtualFile,
    val driverType: String?,
)
