// The Kotlin front end: analyses a plugin's Kotlin sources with the Kotlin Analysis API, standalone, checks them
// against the supported language (docs/kotlin-subset.md) and lowers each file into the Lua tree. It is written in
// Kotlin, the language the Analysis API is made for, and runs in a process of its own: the Analysis API brings the
// IntelliJ platform with it and keeps global state.

plugins {
	`java-library`
	alias(libs.plugins.kotlin.jvm)
}

description = "Oneiro's Kotlin front end, which compiles RS3 plugins written in Kotlin to Lua 5.4."

kotlin {
	explicitApi()
	compilerOptions {
		allWarningsAsErrors = true
		// Resolving a reference or an index expression to its symbol is still marked experimental in the 2.4 Analysis
		// API and in the PSI under it
		optIn.addAll("org.jetbrains.kotlin.analysis.api.KaExperimentalApi", "org.jetbrains.kotlin.psi.KtExperimentalApi")
	}
}

dependencies {
	api(project(":oneiro-lowering"))
	// The compiler's settings, which the build writes for the worker that runs this front end
	implementation(project(":oneiro-plugin-model"))
	// For constants such as LuaConstants.PREFIX, which the compiler copies into the code that reads them; the front
	// end finds the annotations themselves by name, in the plugin's own classpath
	compileOnly(project(":oneiro-api"))
	// The Analysis API and the IntelliJ platform modules it runs on, which JetBrains publishes only to its own
	// repositories. A plugin's build resolves them from there: the Gradle plugin adds those repositories, for these
	// artifacts alone, to a build that applies the Kotlin JVM plugin
	implementation(libs.bundles.intellij.platform)
	// The Analysis API's artifacts declare no dependencies, so each is listed, and used without transitives
	libs.bundles.kotlin.analysis.api.get().forEach {
		implementation("${it.module}:${it.version}") {
			isTransitive = false
		}
	}
	implementation(libs.caffeine)
	implementation(libs.kotlinx.collections.immutable)
}
