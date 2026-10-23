// Generates the RS3 bindings on the developer's machine: parses runescape-api-stubs (LuaCATS files and csvs),
// applies the hand-kept overrides file, and writes Java sources, their compiled classes and the chunked constants as
// jars. Nothing derived from the stubs is published; the Gradle plugin runs this in a worker on JDK 25.

plugins {
	`java-library`
}

dependencies {
	// The generated sources are compiled against the binding annotations and JSpecify
	implementation(project(":api"))
	implementation(libs.jspecify)
}

tasks.test {
	// The test against the real stubs runs when they are checked out next to Oneiro, as in the workspace
	val stubs = rootProject.file("../runescape-api-stubs")
	systemProperty("oneiro.stubs", stubs.absolutePath)
	if (stubs.resolve("types.lua").isFile) {
		inputs.dir(stubs.resolve("generated")).withPropertyName("stubsGenerated")
		inputs.dir(stubs.resolve("csvs")).withPropertyName("stubsCsvs")
		inputs.file(stubs.resolve("types.lua")).withPropertyName("stubsTypes")
	}
}
