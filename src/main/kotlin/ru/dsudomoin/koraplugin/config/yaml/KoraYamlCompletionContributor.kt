package ru.dsudomoin.koraplugin.config.yaml

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import ru.dsudomoin.koraplugin.config.KoraConfigCompletionUtil
import ru.dsudomoin.koraplugin.util.KoraLibraryUtil

class KoraYamlCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val project = position.project
        if (!KoraLibraryUtil.hasKoraLibrary(project)) return

        val keyValue = PsiTreeUtil.getParentOfType(position, YAMLKeyValue::class.java) ?: return
        val parentPath = getParentPath(keyValue)
        val variants = KoraConfigCompletionUtil.collectVariants(project, parentPath)
        for (variant in variants) {
            result.addElement(variant as LookupElement)
        }
    }

    private fun getParentPath(keyValue: YAMLKeyValue): String? {
        val parent = keyValue.parentMapping?.parent as? YAMLKeyValue ?: return null
        return buildYamlKeyPath(parent)
    }
}
