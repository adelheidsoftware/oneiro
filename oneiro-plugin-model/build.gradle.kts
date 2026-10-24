// The plugin as the build describes it, apart from its code: the manifest and its plugin.json, the checks of the
// folder that ships, the typed Resources and Urls classes, and the settings the compiler reads. Compiled for Java 17,
// because the Gradle plugin uses it inside the Gradle daemon, which may run on an older JDK than Oneiro's compiler.

plugins {
	`java-library`
}

description = "An RS3 plugin apart from its code, for Oneiro: its manifest, the checks of the folder that ships, and " +
	"its typed resources."

tasks.withType<JavaCompile>().configureEach {
	options.release = 17
}
