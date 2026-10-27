package org.adelheid.oneiro.kotlin

import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path

/**
 * Standalone Analysis API sessions: one source module, the plugin's files, which depends on the JDK that runs Oneiro
 * and on the jars the plugin compiles against. For the dev source set, a module of the main set's files comes too,
 * which the dev module depends on as kotlinc compiles the dev set against the main set: seeing its declarations as
 * they are written, but not its internal ones. A session lives until its disposable is disposed.
 */
internal object AnalysisSessions {

	/**
	 * The Kotlin files a session parsed.
	 *
	 * @property lowered the files of the source set being compiled
	 * @property main for the dev source set, the main set's, read for what their classes are and not lowered
	 */
	class Files(val lowered: List<KtFile>, val main: List<KtFile>)

	/** A session over the sources, with the main set's sources for the dev set, and the Kotlin files it parsed. */
	fun build(disposable: Disposable, sources: List<Path>, mainSources: List<Path>, classpath: List<Path>): Files {
		var mainModule: KaModule? = null
		val session = buildStandaloneAnalysisAPISession(disposable) {
			buildKtModuleProvider {
				platform = JvmPlatforms.defaultJvmPlatform
				val jdk = addModule(buildKtSdkModule {
					addBinaryRootsFromJdkHome(Path.of(System.getProperty("java.home")), isJre = false)
					platform = JvmPlatforms.defaultJvmPlatform
					libraryName = "JDK"
				})
				val libraries = addModule(buildKtLibraryModule {
					addBinaryRoots(classpath)
					platform = JvmPlatforms.defaultJvmPlatform
					libraryName = "classpath"
				})
				val main = if (mainSources.isEmpty()) null else addModule(buildKtSourceModule {
					addSourceRoots(mainSources)
					platform = JvmPlatforms.defaultJvmPlatform
					moduleName = "main"
					addRegularDependency(jdk)
					addRegularDependency(libraries)
				})
				mainModule = main
				addModule(buildKtSourceModule {
					addSourceRoots(sources)
					platform = JvmPlatforms.defaultJvmPlatform
					moduleName = "plugin"
					addRegularDependency(jdk)
					addRegularDependency(libraries)
					main?.let(::addRegularDependency)
				})
			}
		}
		val (main, lowered) = session.modulesWithFiles.entries.partition { it.key == mainModule }
		return Files(lowered.flatMap { it.value }.filterIsInstance<KtFile>(),
			main.flatMap { it.value }.filterIsInstance<KtFile>())
	}
}
