package org.adelheid.oneiro.kotlin

import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path

/**
 * Standalone Analysis API sessions: one source module, the plugin's files, which depends on the JDK that runs Oneiro
 * and on the jars the plugin compiles against. A session lives until its disposable is disposed.
 */
internal object AnalysisSessions {

	/** A session over the sources, and the Kotlin files it parsed. */
	fun build(disposable: Disposable, sources: List<Path>, classpath: List<Path>): List<KtFile> {
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
				addModule(buildKtSourceModule {
					addSourceRoots(sources)
					platform = JvmPlatforms.defaultJvmPlatform
					moduleName = "plugin"
					addRegularDependency(jdk)
					addRegularDependency(libraries)
				})
			}
		}
		return session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()
	}
}
