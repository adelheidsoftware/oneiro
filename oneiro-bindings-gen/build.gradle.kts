// Generates the RS3 bindings on the developer's machine: parses runescape-api-stubs (LuaCATS files and csvs),
// applies the hand-kept overrides file, and writes Java sources, their compiled classes and the chunked constants as
// jars. Nothing derived from the stubs is published; the Gradle plugin runs this in a worker on JDK 25.

plugins {
	`java-library`
}

description = "Generates Oneiro's Java bindings of the RS3 Client Plugin API from runescape-api-stubs."

dependencies {
	// The generated sources are compiled against the binding annotations and JSpecify
	implementation(project(":oneiro-api"))
	implementation(libs.jspecify)
	// The facts checkPlugin reads, written next to the RS3 bindings
	implementation(project(":oneiro-plugin-model"))
}

// The real stubs, checked out next to Oneiro, for the test against them and for the audit of the client's members
val stubs = rootProject.file("../runescape-api-stubs")

// The audit of the client's members (ClientAudit), which Oneiro's maintainers run after a client update; it is no part
// of the published jar
val audit: SourceSet = sourceSets.create("audit") {
	compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
	runtimeClasspath += output + compileClasspath + sourceSets.main.get().runtimeClasspath
}
sourceSets.test {
	compileClasspath += audit.output
	runtimeClasspath += audit.output
}

tasks.register<JavaExec>("auditClient") {
	description = "Compares the members the API probe listed from the client's objects with the bindings: " +
		"--args=<log>, a path from Oneiro's folder."
	group = "verification"
	classpath = audit.runtimeClasspath
	mainClass = "org.adelheid.oneiro.bindings.ClientAudit"
	workingDir = rootProject.projectDir
	systemProperty("oneiro.stubs", stubs.absolutePath)
}

tasks.test {
	// The test against the real stubs runs when they are checked out next to Oneiro, at ../runescape-api-stubs
	systemProperty("oneiro.stubs", stubs.absolutePath)
	if (stubs.resolve("types.lua").isFile) {
		inputs.dir(stubs.resolve("generated")).withPropertyName("stubsGenerated")
		inputs.dir(stubs.resolve("csvs")).withPropertyName("stubsCsvs")
		inputs.file(stubs.resolve("types.lua")).withPropertyName("stubsTypes")
	}
	// The LuaCATS reader's tests on real Lua run on the libraries and plugins checked out next to Oneiro
	val blueprintHelper = rootProject.file("../Blueprint-Helper")
	val zyklib = rootProject.file("../runescape-api-zyklib")
	systemProperty("oneiro.blueprintHelper", blueprintHelper.absolutePath)
	systemProperty("oneiro.zyklib", zyklib.absolutePath)
	if (blueprintHelper.resolve("src").isDirectory) {
		inputs.dir(blueprintHelper.resolve("src")).withPropertyName("blueprintHelperSources")
	}
	if (zyklib.resolve("src").isDirectory) {
		inputs.dir(zyklib.resolve("src")).withPropertyName("zyklibSources")
	}
}
