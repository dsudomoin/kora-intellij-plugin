package ru.dsudomoin.koraplugin.index

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.*
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.io.DataInput
import java.io.DataOutput

enum class ProviderKind {
    COMPONENT_CLASS,
    FACTORY_METHOD,
}

data class ProviderIndexEntry(
    val kind: ProviderKind,
    val classFqn: String,
    val methodName: String?,
)

class KoraProviderIndex : FileBasedIndexExtension<String, List<ProviderIndexEntry>>() {

    override fun getName(): ID<String, List<ProviderIndexEntry>> = NAME

    override fun getVersion(): Int = 7

    override fun dependsOnFileContent(): Boolean = true

    override fun getInputFilter(): FileBasedIndex.InputFilter {
        return FileBasedIndex.InputFilter { file ->
            val name = file.name
            name.endsWith(".java") || name.endsWith(".kt")
        }
    }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<ProviderIndexEntry>> = ProviderIndexEntryListExternalizer

    override fun getIndexer(): DataIndexer<String, List<ProviderIndexEntry>, FileContent> {
        return DataIndexer { inputData ->
            val map = mutableMapOf<String, MutableList<ProviderIndexEntry>>()
            val psiFile = inputData.psiFile

            when (psiFile) {
                is PsiJavaFile -> {
                    val classes = KoraIndexUtil.collectClasses(psiFile)
                    for (psiClass in classes) {
                        if (KoraIndexUtil.isComponentClass(psiClass)) {
                            val classFqn = psiClass.qualifiedName ?: continue
                            val entry = ProviderIndexEntry(ProviderKind.COMPONENT_CLASS, classFqn, null)
                            map.getOrPut(classFqn) { mutableListOf() }.add(entry)
                        }
                        if (KoraIndexUtil.isKoraModuleClass(psiClass)) {
                            val containerFqn = psiClass.qualifiedName ?: continue
                            for (method in psiClass.methods) {
                                if (!KoraIndexUtil.isFactoryMethod(method)) continue
                                val returnTypeFqn = KoraIndexUtil.getReturnTypeFqnInFile(method) ?: continue
                                val entry = ProviderIndexEntry(ProviderKind.FACTORY_METHOD, containerFqn, method.name)
                                map.getOrPut(returnTypeFqn) { mutableListOf() }.add(entry)
                            }
                        }
                    }
                }
                is KtFile -> {
                    // Use native Kotlin PSI — NOT light classes (psiFile.classes triggers cross-file resolution)
                    val ktClasses = KoraIndexUtil.collectKtClasses(psiFile)
                    for (cls in ktClasses) {
                        val classFqn = KoraIndexUtil.getKtClassFqn(cls) ?: continue
                        if (KoraIndexUtil.isComponentKtClass(cls)) {
                            val entry = ProviderIndexEntry(ProviderKind.COMPONENT_CLASS, classFqn, null)
                            map.getOrPut(classFqn) { mutableListOf() }.add(entry)
                        }
                        if (KoraIndexUtil.isKoraModuleKtClass(cls)) {
                            for (func in cls.declarations.filterIsInstance<KtNamedFunction>()) {
                                if (!KoraIndexUtil.isKtFactoryFunction(func)) continue
                                val returnTypeFqn = KoraIndexUtil.getKtReturnTypeFqnInFile(func) ?: continue
                                val entry = ProviderIndexEntry(ProviderKind.FACTORY_METHOD, classFqn, func.name)
                                map.getOrPut(returnTypeFqn) { mutableListOf() }.add(entry)
                            }
                        }
                    }
                }
                else -> return@DataIndexer emptyMap()
            }

            map
        }
    }
}

private object ProviderIndexEntryListExternalizer : DataExternalizer<List<ProviderIndexEntry>> {

    override fun save(out: DataOutput, value: List<ProviderIndexEntry>) {
        out.writeInt(value.size)
        for (entry in value) {
            out.writeInt(entry.kind.ordinal)
            out.writeUTF(entry.classFqn)
            out.writeBoolean(entry.methodName != null)
            if (entry.methodName != null) {
                out.writeUTF(entry.methodName)
            }
        }
    }

    override fun read(input: DataInput): List<ProviderIndexEntry> {
        val size = input.readInt()
        val result = ArrayList<ProviderIndexEntry>(size)
        repeat(size) {
            val kind = ProviderKind.entries[input.readInt()]
            val classFqn = input.readUTF()
            val hasMethod = input.readBoolean()
            val methodName = if (hasMethod) input.readUTF() else null
            result.add(ProviderIndexEntry(kind, classFqn, methodName))
        }
        return result
    }
}

val NAME: ID<String, List<ProviderIndexEntry>> = ID.create("kora.provider.index")

/**
 * Returns providers from the index, or null if the index is not available (e.g. in light tests).
 */
fun getProviders(typeFqn: String, project: Project, scope: GlobalSearchScope): List<ProviderIndexEntry>? {
    return try {
        val result = mutableListOf<ProviderIndexEntry>()
        FileBasedIndex.getInstance().getValues(NAME, typeFqn, scope).forEach { entries ->
            result.addAll(entries)
        }
        result
    } catch (_: IllegalStateException) {
        null
    }
}

fun getAllKeys(project: Project): Collection<String> {
    return FileBasedIndex.getInstance().getAllKeys(NAME, project)
}