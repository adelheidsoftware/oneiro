package org.adelheid.oneiro.kotlin

import com.intellij.openapi.util.Disposer
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path
import org.adelheid.oneiro.kotlin.core.Annotations
import org.adelheid.oneiro.kotlin.core.FileLowering
import org.adelheid.oneiro.kotlin.core.SourceText
import org.adelheid.oneiro.kotlin.language.ObjectEnums
import org.adelheid.oneiro.lowering.CompileResult
import org.adelheid.oneiro.lowering.Diagnostic
import org.adelheid.oneiro.lowering.LocalNames
import org.adelheid.oneiro.lowering.LuaModule
import org.adelheid.oneiro.lowering.PluginFacts
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

/**
 * Compiles a plugin's Kotlin sources to Lua modules. Each call builds a standalone Analysis API session over the
 * sources and their classpath, checks that they compile, lowers every Kotlin file into a module, and disposes of the
 * session. Java sources among them, such as the generated `Resources` and `Urls`, are read for their types and not
 * lowered. When the sources have errors of Kotlin's own, those are the only diagnostics, as javac's are for Java.
 */
public object KotlinFrontend {

	/**
	 * What compiling needs besides the sources.
	 *
	 * @property rootPackage the package whose subpackages become folders under `src/`; empty to take the package of
	 *     the class that extends `Plugin`, or `DevPlugin` in the dev source set
	 * @property classpath the jars the sources compile against: the RS3 bindings, Oneiro's API, JSpecify and the
	 *     Kotlin standard library
	 * @property plugin the plugin's identifier, permissions and manifest flags
	 */
	public class Options(
		public val rootPackage: String,
		public val classpath: List<Path>,
		public val plugin: PluginFacts,
	)

	@JvmStatic
	public fun compile(sources: List<Path>, options: Options): CompileResult {
		val disposable = Disposer.newDisposable("Oneiro's Kotlin front end")
		try {
			val files = AnalysisSessions.build(disposable, sources, options.classpath)
				.sortedBy { it.virtualFilePath }
			if (files.isEmpty()) {
				return CompileResult(listOf(), listOf())
			}
			val kotlinErrors = files.flatMap(::kotlinErrors)
			if (kotlinErrors.isNotEmpty()) {
				return CompileResult(listOf(), kotlinErrors)
			}
			val entries = entryClasses(files)
			val rootPackage = options.rootPackage.ifEmpty { entries.firstOrNull()?.packageName }
			if (rootPackage == null) {
				val noRoot = SourceText(files.first()).diagnostic(Diagnostic.Severity.ERROR, 0, "No class extends"
					+ " Plugin, so Oneiro cannot tell the plugin's root package; name it in the build: oneiro {"
					+ " rootPackage = \"com.example.herbs\" }.")
				return CompileResult(listOf(), listOf(noRoot))
			}
			val objectEnums = mutableSetOf<String>()
			files.forEach { file -> analyze(file) { ObjectEnums.find(this, file, objectEnums) } }
			val compilation = Compilation(Options(rootPackage, options.classpath, options.plugin), entries,
				extendedClasses(files), objectEnums)
			val modules = mutableListOf<LuaModule>()
			val diagnostics = mutableListOf<Diagnostic>()
			// The file each Lua file comes from
			val sources = mutableMapOf<String, KtFile>()
			for (file in files) {
				analyze(file) {
					val (module, lowering) = lower(this, file, compilation)
					val first = module?.let { sources.putIfAbsent(it.path(), file) }
					if (first != null) {
						// Such as Drops.kt of top-level functions beside a file that declares an object Drops
						lowering.error(file.declarations.first(), "The Lua of this file would be ${module.path()},"
							+ " which ${first.name} makes too; rename one of them.")
					}
					module?.let(modules::add)
					diagnostics += lowering.diagnostics()
				}
			}
			val result = CompileResult(modules, diagnostics)
			return if (result.hasErrors()) CompileResult(listOf(), diagnostics) else result
		} finally {
			Disposer.dispose(disposable)
		}
	}

	/**
	 * Lowers one file into its module. A local whose name equals a helper the module uses, or a Lua global it reads
	 * such as `config`, would hide it, so when that happens the file is lowered a second time with those names taken.
	 */
	private fun lower(session: KaSession, file: KtFile, compilation: Compilation): Pair<LuaModule?, FileLowering> {
		val first = FileLowering(session, file, compilation, LocalNames(setOf()))
		val module = first.lower()
		val clashes = first.declaredNames() intersect first.takenNames()
		if (clashes.isEmpty()) {
			return module to first
		}
		val second = FileLowering(session, file, compilation, LocalNames(clashes))
		return second.lower() to second
	}

	/** The errors Kotlin itself finds in a file, such as a type mismatch or an unresolved name. */
	private fun kotlinErrors(file: KtFile): List<Diagnostic> = analyze(file) {
		val source = SourceText(file)
		file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
			.filter { it.severity == KaSeverity.ERROR }
			.map { source.diagnostic(Diagnostic.Severity.ERROR, it.textRanges.first().startOffset, it.defaultMessage) }
	}

	/** The classes that extend `Plugin` or `DevPlugin`, in the order of their files. */
	private fun entryClasses(files: List<KtFile>): List<EntryClass> = files.flatMap { file ->
		analyze(file) {
			file.declarations.filterIsInstance<KtClass>().mapNotNull { declaration ->
				val bases = declaration.classSymbol?.superTypes?.mapNotNull { it.expandedSymbol?.classId }.orEmpty()
				val name = declaration.fqName?.asString() ?: return@mapNotNull null
				when {
					Annotations.PLUGIN in bases -> EntryClass(name, file.packageFqName.asString(), false)
					Annotations.DEV_PLUGIN in bases -> EntryClass(name, file.packageFqName.asString(), true)
					else -> null
				}
			}
		}
	}

	/** The classes of the sources that another class of theirs extends, by qualified name. */
	private fun extendedClasses(files: List<KtFile>): Set<String> = files.flatMap { file ->
		analyze(file) {
			PsiTreeUtil.collectElementsOfType(file, KtClassOrObject::class.java).flatMap { declaration ->
				declaration.classSymbol?.superTypes.orEmpty()
					.mapNotNull { it.expandedSymbol?.classId?.asFqNameString() }
			}
		}
	}.toSet()

	/**
	 * What lowering a file needs to know about the whole compilation.
	 *
	 * @property extended the classes another class extends, by qualified name, which a type test walks up to
	 * @property objectEnums the enums whose constants are objects, by qualified name; see [ObjectEnums]
	 */
	internal class Compilation(
		val options: Options,
		val entries: List<EntryClass>,
		val extended: Set<String>,
		val objectEnums: Set<String>,
	)

	/**
	 * A class that extends `Plugin`, or `DevPlugin` when [dev], and so becomes `main.lua` or `dev/main.lua`.
	 *
	 * @property packageName the package of its file, the plugin's root package unless the build names another
	 */
	internal class EntryClass(val qualifiedName: String, val packageName: String, val dev: Boolean)
}
