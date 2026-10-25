package org.adelheid.oneiro.kotlin

import org.adelheid.oneiro.lowering.Diagnostic
import org.adelheid.oneiro.lowering.LuaOutput
import org.adelheid.oneiro.lowering.PluginFacts
import org.adelheid.oneiro.plugin.CompilerSettings
import java.io.File
import java.nio.file.Path

/**
 * The Kotlin front end as the Gradle plugin runs it, in a worker process of its own on JDK 25: it reads the
 * compiler's settings, which the build writes, compiles a source set's Kotlin, and writes the Lua and its line map. The
 * Gradle plugin calls it by name, because it is compiled for an older Java and cannot link against this code.
 */
public object KotlinCompiler {

	/**
	 * Compiles a source set and writes its Lua when nothing is wrong.
	 *
	 * @param settings the path of the compiler's settings file
	 * @param sources the source set's Kotlin files, and the Java files they use, such as the generated `Resources`,
	 *     joined by the platform's path separator
	 * @param classpath the jars and folders the sources compile against, joined by the path separator
	 * @return the diagnostics as kotlinc prints them; when one is an error, starting with `e: `, nothing is written
	 */
	@JvmStatic
	public fun compileForBuild(settings: String, sources: String, classpath: String): List<String> {
		val compiler = CompilerSettings.read(Path.of(settings))
		val plugin = PluginFacts(compiler.id(), compiler.permissions(), compiler.flags(), compiler.luaFiles(),
			compiler.dev())
		val result = KotlinFrontend.compile(paths(sources),
			KotlinFrontend.Options(compiler.rootPackage(), paths(classpath), plugin))
		// With warningsAsErrors, a warning fails the build like an error
		val diagnostics = result.diagnostics().map {
			if (compiler.warningsAsErrors()) Diagnostic(Diagnostic.Severity.ERROR, it.file(), it.line(), it.column(),
				it.message(), it.sourceLine(), it.position()) else it
		}
		if (diagnostics.none { it.severity() == Diagnostic.Severity.ERROR }) {
			LuaOutput.write(result.modules(), compiler.luaOutput(), compiler.lineMap())
		}
		return diagnostics.map(Diagnostic::kotlincFormat)
	}

	private fun paths(joined: String): List<Path> =
		joined.split(File.pathSeparator).filter(String::isNotEmpty).map(Path::of)
}
