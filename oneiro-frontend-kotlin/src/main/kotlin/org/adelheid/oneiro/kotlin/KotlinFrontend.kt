package org.adelheid.oneiro.kotlin

import com.intellij.openapi.util.Disposer
import org.adelheid.oneiro.lowering.CompileResult
import org.adelheid.oneiro.lowering.Diagnostic
import org.adelheid.oneiro.lowering.LuaModule
import org.adelheid.oneiro.lowering.PluginFacts
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path

/**
 * Compiles a plugin's Kotlin sources to Lua modules. Each call builds a standalone Analysis API session over the
 * sources and their classpath, checks that they compile, lowers every file into a module, and disposes of the session.
 * When the sources have errors of Kotlin's own, those are the only diagnostics, as javac's are for Java.
 */
public object KotlinFrontend {

	/**
	 * What compiling needs besides the sources.
	 *
	 * @property rootPackage the package whose subpackages become folders under `src/`
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
			val kotlinErrors = files.flatMap(::kotlinErrors)
			if (kotlinErrors.isNotEmpty()) {
				return CompileResult(listOf(), kotlinErrors)
			}
			val modules = mutableListOf<LuaModule>()
			val diagnostics = mutableListOf<Diagnostic>()
			for (file in files) {
				analyze(file) {
					val lowering = FileLowering(this, file, options)
					lowering.lower()?.let(modules::add)
					diagnostics += lowering.diagnostics()
				}
			}
			val result = CompileResult(modules, diagnostics)
			return if (result.hasErrors()) CompileResult(listOf(), diagnostics) else result
		} finally {
			Disposer.dispose(disposable)
		}
	}

	/** The errors Kotlin itself finds in a file, such as a type mismatch or an unresolved name. */
	private fun kotlinErrors(file: KtFile): List<Diagnostic> = analyze(file) {
		val source = SourceText(file)
		file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
			.filter { it.severity == KaSeverity.ERROR }
			.map { source.diagnostic(Diagnostic.Severity.ERROR, it.textRanges.first().startOffset, it.defaultMessage) }
	}
}
