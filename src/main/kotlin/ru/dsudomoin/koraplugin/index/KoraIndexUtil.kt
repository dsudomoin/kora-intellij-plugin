package ru.dsudomoin.koraplugin.index

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import ru.dsudomoin.koraplugin.KoraAnnotations

/**
 * Utility methods for Kora indexes.
 *
 * Indexer methods (isComponentClass, isKoraModuleClass, getReturnTypeFqnInFile, etc.)
 * use ONLY file-local PSI data — never call resolve(), hasAnnotation(), or other
 * cross-file operations. This is required by the FileBasedIndex contract.
 *
 * Query-time methods (getRawTypeFqn) may use full PSI resolution and must NOT be
 * called from indexers.
 */
object KoraIndexUtil {

    private const val KORA_SUBMODULE_PROCESSOR = "ru.tinkoff.kora.kora.app.ksp.KoraSubmoduleProcessor"

    // ========== Indexer-safe methods (file-local PSI only) ==========

    fun isComponentClass(psiClass: PsiClass): Boolean {
        return hasAnnotationInFile(psiClass, "Component", KoraAnnotations.COMPONENT) ||
            hasAnnotationInFile(psiClass, "Repository", KoraAnnotations.REPOSITORY)
    }

    fun isKoraModuleClass(psiClass: PsiClass): Boolean {
        if (hasAnnotationInFile(psiClass, "KoraApp", KoraAnnotations.KORA_APP)) return true
        if (hasAnnotationInFile(psiClass, "Module", KoraAnnotations.MODULE)) return true
        if (hasAnnotationInFile(psiClass, "KoraSubmodule", KoraAnnotations.KORA_SUBMODULE)) return true
        if (isKoraGeneratedSubmodule(psiClass)) return true
        return false
    }

    fun isKoraGeneratedSubmodule(psiClass: PsiClass): Boolean {
        if (!psiClass.isInterface) return false
        val ann = findAnnotationByShortName(psiClass, "Generated") ?: return false
        if (!isAnnotationMatchesFqn(ann, KoraAnnotations.GENERATED)) return false
        // Read value attribute — it's a string literal within the same file, safe to access
        val value = ann.parameterList.attributes
            .firstOrNull { it.name == "value" || it.name == null }
            ?.value
        val text = (value as? PsiLiteralExpression)?.value as? String ?: return false
        return text == KORA_SUBMODULE_PROCESSOR
    }

    /**
     * Get return type FQN using only file-local data (type element text + imports).
     */
    fun getReturnTypeFqnInFile(method: PsiMethod): String? {
        val typeElement = method.returnTypeElement ?: return null
        val typeText = typeElement.text.trim()
        if (typeText == "void") return null
        return resolveRawTypeName(typeText, method.containingFile)
    }

    /**
     * Get parameter type FQN, unwrapping All<T>/ValueOf<T>, using only file-local data.
     */
    fun unwrapAndGetRawFqnInFile(param: PsiParameter): String? {
        val typeElement = param.typeElement ?: return null
        return unwrapTypeText(typeElement.text.trim(), param.containingFile)
    }

    fun isFactoryMethod(method: PsiMethod): Boolean {
        if (method.isConstructor) return false
        val typeElement = method.returnTypeElement ?: return false
        return typeElement.text.trim() != "void"
    }

    fun collectClasses(javaFile: PsiJavaFile): List<PsiClass> {
        val result = mutableListOf<PsiClass>()
        for (cls in javaFile.classes) {
            collectClassesRecursive(cls, result)
        }
        return result
    }

    // ========== Kotlin-native PSI methods for indexer (file-local only) ==========

    fun collectKtClasses(ktFile: KtFile): List<KtClassOrObject> {
        val result = mutableListOf<KtClassOrObject>()
        for (decl in ktFile.declarations) {
            if (decl is KtClassOrObject) {
                collectKtClassesRecursive(decl, result)
            }
        }
        return result
    }

    private fun collectKtClassesRecursive(cls: KtClassOrObject, result: MutableList<KtClassOrObject>) {
        result.add(cls)
        for (decl in cls.declarations) {
            if (decl is KtClassOrObject) {
                collectKtClassesRecursive(decl, result)
            }
        }
    }

    fun isComponentKtClass(cls: KtClassOrObject): Boolean {
        return hasKtAnnotation(cls, "Component", KoraAnnotations.COMPONENT) ||
            hasKtAnnotation(cls, "Repository", KoraAnnotations.REPOSITORY)
    }

    fun isKoraModuleKtClass(cls: KtClassOrObject): Boolean {
        if (hasKtAnnotation(cls, "KoraApp", KoraAnnotations.KORA_APP)) return true
        if (hasKtAnnotation(cls, "Module", KoraAnnotations.MODULE)) return true
        if (hasKtAnnotation(cls, "KoraSubmodule", KoraAnnotations.KORA_SUBMODULE)) return true
        if (isKoraGeneratedKtSubmodule(cls)) return true
        return false
    }

    fun getKtClassFqn(cls: KtClassOrObject): String? {
        return cls.fqName?.asString()
    }

    fun isKtFactoryFunction(func: KtNamedFunction): Boolean {
        val typeRef = func.typeReference ?: return false
        val typeText = typeRef.text.trim()
        return typeText != "Unit"
    }

    fun getKtReturnTypeFqnInFile(func: KtNamedFunction): String? {
        val typeRef = func.typeReference ?: return null
        val typeText = typeRef.text.trim()
        if (typeText == "Unit") return null
        return resolveRawTypeName(typeText, func.containingFile)
    }

    fun unwrapAndGetRawFqnFromKtParam(param: KtParameter): String? {
        val typeRef = param.typeReference ?: return null
        val typeText = typeRef.text.trim()
        return unwrapTypeText(typeText, param.containingFile)
    }

    private fun hasKtAnnotation(cls: KtClassOrObject, shortName: String, fqn: String): Boolean {
        val hasAnnotation = cls.annotationEntries.any { entry ->
            entry.shortName?.asString() == shortName
        }
        if (!hasAnnotation) return false
        return isFqnAccessible(cls.containingKtFile, fqn)
    }

    private fun isKoraGeneratedKtSubmodule(cls: KtClassOrObject): Boolean {
        if (cls !is KtClass || !cls.isInterface()) return false
        val entry = cls.annotationEntries.find { it.shortName?.asString() == "Generated" } ?: return false
        if (!isFqnAccessible(cls.containingKtFile, KoraAnnotations.GENERATED)) return false
        val firstArg = entry.valueArguments.firstOrNull() ?: return false
        val argText = firstArg.getArgumentExpression()?.text?.removeSurrounding("\"") ?: return false
        return argText == KORA_SUBMODULE_PROCESSOR
    }

    // ========== Query-time methods (may use PSI resolution) ==========

    /**
     * Resolve raw type FQN from a PsiType using full PSI resolution.
     * NOT safe for indexer context — only use at query time.
     */
    fun getRawTypeFqn(type: PsiType): String? {
        if (type is PsiClassType) {
            val resolved = type.resolve() ?: return null
            return resolved.qualifiedName
        }
        return type.canonicalText
    }

    // ========== Private helpers ==========

    private fun collectClassesRecursive(psiClass: PsiClass, result: MutableList<PsiClass>) {
        result.add(psiClass)
        for (inner in psiClass.innerClasses) {
            collectClassesRecursive(inner, result)
        }
    }

    private fun hasAnnotationInFile(psiClass: PsiClass, shortName: String, fqn: String): Boolean {
        val ann = findAnnotationByShortName(psiClass, shortName) ?: return false
        return isAnnotationMatchesFqn(ann, fqn)
    }

    private fun findAnnotationByShortName(psiClass: PsiClass, shortName: String): PsiAnnotation? {
        val modList = psiClass.modifierList ?: return null
        return modList.annotations.find { ann ->
            ann.nameReferenceElement?.referenceName == shortName
        }
    }

    private fun isAnnotationMatchesFqn(annotation: PsiAnnotation, expectedFqn: String): Boolean {
        // Check if annotation reference is fully qualified in source
        val refText = annotation.nameReferenceElement?.text ?: return false
        if (refText == expectedFqn) return true

        // Short name must match
        val shortName = expectedFqn.substringAfterLast('.')
        if (refText != shortName) return false

        // Verify via imports
        return isFqnAccessible(annotation.containingFile, expectedFqn)
    }

    private fun isFqnAccessible(file: PsiFile?, fqn: String): Boolean {
        if (file == null) return false
        val packagePrefix = fqn.substringBeforeLast('.')

        when (file) {
            is PsiJavaFile -> {
                val importList = file.importList ?: return false
                for (stmt in importList.allImportStatements) {
                    val importRef = stmt.importReference?.qualifiedName ?: continue
                    if (importRef == fqn) return true
                    if (stmt.isOnDemand && importRef == packagePrefix) return true
                }
                if (file.packageName == packagePrefix) return true
            }
            is KtFile -> {
                for (directive in file.importDirectives) {
                    val importPath = directive.importPath?.pathStr ?: continue
                    if (importPath == fqn) return true
                    if (directive.isAllUnder && importPath == packagePrefix) return true
                }
                if (file.packageFqName.asString() == packagePrefix) return true
            }
        }
        return false
    }

    private fun unwrapTypeText(typeText: String, file: PsiFile?): String? {
        val rawName = typeText.substringBefore('<').trim()
        val hasGenerics = '<' in typeText

        if (hasGenerics) {
            val isAll = rawName == "All" || rawName == KoraAnnotations.ALL_TYPE
            val isValueOf = rawName == "ValueOf" || rawName == KoraAnnotations.VALUE_OF_TYPE

            if (isAll || isValueOf) {
                val expectedFqn = if (isAll) KoraAnnotations.ALL_TYPE else KoraAnnotations.VALUE_OF_TYPE
                if (rawName.contains('.') || (file != null && isFqnAccessible(file, expectedFqn))) {
                    val innerType = typeText.substring(typeText.indexOf('<') + 1, typeText.lastIndexOf('>')).trim()
                    return unwrapTypeText(innerType, file)
                }
            }
        }

        return resolveRawTypeName(typeText, file)
    }

    private fun resolveRawTypeName(typeText: String, file: PsiFile?): String? {
        val rawName = typeText.substringBefore('<').substringBefore('[').trim()

        // Primitive types
        if (rawName in PRIMITIVE_TYPES) return rawName

        // Already fully qualified
        if ('.' in rawName) return rawName

        if (file == null) return rawName

        // Resolve via imports
        return resolveShortNameInFile(file, rawName) ?: rawName
    }

    private fun resolveShortNameInFile(file: PsiFile, shortName: String): String? {
        when (file) {
            is PsiJavaFile -> {
                // Check explicit imports first
                for (stmt in file.importList?.importStatements ?: emptyArray()) {
                    if (stmt.isOnDemand) continue
                    val importFqn = stmt.qualifiedName ?: continue
                    if (importFqn.endsWith(".$shortName")) return importFqn
                }
                // Fallback: same package
                val pkg = file.packageName
                return if (pkg.isNotEmpty()) "$pkg.$shortName" else shortName
            }
            is KtFile -> {
                for (directive in file.importDirectives) {
                    if (directive.isAllUnder) continue
                    val importPath = directive.importPath?.pathStr ?: continue
                    if (importPath.endsWith(".$shortName")) return importPath
                }
                val pkg = file.packageFqName.asString()
                return if (pkg.isNotEmpty()) "$pkg.$shortName" else shortName
            }
            else -> return null
        }
    }

    private val PRIMITIVE_TYPES = setOf(
        "byte", "short", "int", "long", "float", "double", "char", "boolean",
    )
}
