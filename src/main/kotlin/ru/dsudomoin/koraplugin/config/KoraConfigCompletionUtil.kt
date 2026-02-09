package ru.dsudomoin.koraplugin.config

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project

object KoraConfigCompletionUtil {

    fun collectVariants(project: Project, parentPath: String?): Array<Any> {
        val variants = mutableListOf<LookupElementBuilder>()
        val seen = mutableSetOf<String>()

        // Collect from @ConfigSource classes
        val configSources = ConfigSourceSearch.findAllConfigSources(project)
        for (entry in configSources) {
            val segments = entry.path.split(".")
            val nextSegment = getNextSegment(segments, parentPath)
            if (nextSegment != null && seen.add(nextSegment)) {
                variants.add(
                    LookupElementBuilder.create(nextSegment)
                        .withIcon(AllIcons.FileTypes.Config)
                        .withTypeText("@ConfigSource", true),
                )
            }
        }

        // Collect from annotation-mapped configs
        for (fqn in KoraConfigAnnotationRegistry.allAnnotationFqns) {
            val mapping = KoraConfigAnnotationRegistry.findMappingForAnnotation(fqn) ?: continue
            val prefix = mapping.configPathPrefix ?: continue
            val segments = prefix.split(".")
            val nextSegment = getNextSegment(segments, parentPath)
            if (nextSegment != null && seen.add(nextSegment)) {
                val shortName = fqn.substringAfterLast('.')
                variants.add(
                    LookupElementBuilder.create(nextSegment)
                        .withIcon(AllIcons.Nodes.Annotationtype)
                        .withTypeText("@$shortName", true),
                )
            }
        }

        return variants.toTypedArray()
    }

    private fun getNextSegment(configSegments: List<String>, parentPath: String?): String? {
        if (parentPath == null) {
            return configSegments.firstOrNull()
        }
        val parentSegments = parentPath.split(".")
        if (configSegments.size <= parentSegments.size) return null
        for (i in parentSegments.indices) {
            val actual = ConfigPathResolver.camelToKebab(configSegments[i])
            val expected = ConfigPathResolver.camelToKebab(parentSegments[i])
            if (actual != expected) return null
        }
        return configSegments[parentSegments.size]
    }
}
