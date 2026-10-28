// The plugin as the build describes it, apart from its code: the manifest and its plugin.json, the checks of the
// folder that ships, the typed Resources and Urls classes, and the settings the compiler reads. Compiled for Java 17,
// because the Gradle plugin uses it inside the Gradle daemon, which may run on an older JDK than Oneiro's compiler.

plugins {
	`java-library`
}

dependencies {
	// The manifest, the package checks, the libraries list and the client facts are the RSPC SDK's
	api(libs.rspc.plugin.model)
}

description = "An RS3 plugin apart from its code, for Oneiro: the typed resources, the compiler's settings, and what " +
	"explainError reads."

tasks.withType<JavaCompile>().configureEach {
	options.release = 17
}
