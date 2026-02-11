package ru.dsudomoin.koraplugin.index

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.*
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.io.DataInput
import java.io.DataOutput

data class InjectionSiteIndexEntry(
    val classFqn: String,
    val methodName: String?,
    val paramName: String,
    val isConstructor: Boolean,
)

class KoraInjectionSiteIndex : FileBasedIndexExtension<String, List<InjectionSiteIndexEntry>>() {

    companion object {
        val NAME: ID<String, List<InjectionSiteIndexEntry>> = ID.create("kora.injection.site.index")

        /**
         * Returns sites from the index, or null if the index is not available (e.g. in light tests).
         */
        fun getSites(typeFqn: String, project: Project, scope: GlobalSearchScope): List<InjectionSiteIndexEntry>? {
            return try {
                val result = mutableListOf<InjectionSiteIndexEntry>()
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
    }

    override fun getName(): ID<String, List<InjectionSiteIndexEntry>> = NAME

    override fun getVersion(): Int = 9

    override fun dependsOnFileContent(): Boolean = true

    override fun getInputFilter(): FileBasedIndex.InputFilter {
        return FileBasedIndex.InputFilter { file ->
            val name = file.name
            name.endsWith(".java") || name.endsWith(".kt")
        }
    }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<InjectionSiteIndexEntry>> = InjectionSiteIndexEntryListExternalizer

    override fun getIndexer(): DataIndexer<String, List<InjectionSiteIndexEntry>, FileContent> {
        return DataIndexer { inputData ->
            val map = mutableMapOf<String, MutableList<InjectionSiteIndexEntry>>()
            val psiFile = inputData.psiFile

            when (psiFile) {
                is PsiJavaFile -> {
                    val classes = KoraIndexUtil.collectClasses(psiFile)
                    for (psiClass in classes) {
                        val classFqn = psiClass.qualifiedName ?: continue

                        if (KoraIndexUtil.isComponentClass(psiClass)) {
                            for (constructor in psiClass.constructors) {
                                for (param in constructor.parameterList.parameters) {
                                    val typeFqn = KoraIndexUtil.unwrapAndGetRawFqnInFile(param) ?: continue
                                    val entry = InjectionSiteIndexEntry(
                                        classFqn = classFqn,
                                        methodName = null,
                                        paramName = param.name,
                                        isConstructor = true,
                                    )
                                    map.getOrPut(typeFqn) { mutableListOf() }.add(entry)
                                }
                            }
                        }

                        if (KoraIndexUtil.isKoraModuleClass(psiClass)) {
                            for (method in psiClass.methods) {
                                if (!KoraIndexUtil.isFactoryMethod(method)) continue
                                for (param in method.parameterList.parameters) {
                                    val typeFqn = KoraIndexUtil.unwrapAndGetRawFqnInFile(param) ?: continue
                                    val entry = InjectionSiteIndexEntry(
                                        classFqn = classFqn,
                                        methodName = method.name,
                                        paramName = param.name,
                                        isConstructor = false,
                                    )
                                    map.getOrPut(typeFqn) { mutableListOf() }.add(entry)
                                }
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
                            val primaryCtor = (cls as? KtClass)?.primaryConstructor
                            if (primaryCtor != null) {
                                for (param in primaryCtor.valueParameters) {
                                    val paramName = param.name ?: continue
                                    val typeFqn = KoraIndexUtil.unwrapAndGetRawFqnFromKtParam(param) ?: continue
                                    val entry = InjectionSiteIndexEntry(
                                        classFqn = classFqn,
                                        methodName = null,
                                        paramName = paramName,
                                        isConstructor = true,
                                    )
                                    map.getOrPut(typeFqn) { mutableListOf() }.add(entry)
                                }
                            }
                        }

                        if (KoraIndexUtil.isKoraModuleKtClass(cls)) {
                            for (func in cls.declarations.filterIsInstance<KtNamedFunction>()) {
                                if (!KoraIndexUtil.isKtFactoryFunction(func)) continue
                                val funcName = func.name ?: continue
                                for (param in func.valueParameters) {
                                    val paramName = param.name ?: continue
                                    val typeFqn = KoraIndexUtil.unwrapAndGetRawFqnFromKtParam(param) ?: continue
                                    val entry = InjectionSiteIndexEntry(
                                        classFqn = classFqn,
                                        methodName = funcName,
                                        paramName = paramName,
                                        isConstructor = false,
                                    )
                                    map.getOrPut(typeFqn) { mutableListOf() }.add(entry)
                                }
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

private object InjectionSiteIndexEntryListExternalizer : DataExternalizer<List<InjectionSiteIndexEntry>> {

    override fun save(out: DataOutput, value: List<InjectionSiteIndexEntry>) {
        out.writeInt(value.size)
        for (entry in value) {
            out.writeUTF(entry.classFqn)
            out.writeBoolean(entry.methodName != null)
            if (entry.methodName != null) {
                out.writeUTF(entry.methodName)
            }
            out.writeUTF(entry.paramName)
            out.writeBoolean(entry.isConstructor)
        }
    }

    override fun read(input: DataInput): List<InjectionSiteIndexEntry> {
        val size = input.readInt()
        val result = ArrayList<InjectionSiteIndexEntry>(size)
        repeat(size) {
            val classFqn = input.readUTF()
            val hasMethod = input.readBoolean()
            val methodName = if (hasMethod) input.readUTF() else null
            val paramName = input.readUTF()
            val isConstructor = input.readBoolean()
            result.add(InjectionSiteIndexEntry(classFqn, methodName, paramName, isConstructor))
        }
        return result
    }
}
