// The conformance suite: Java inputs with hand-written expected Lua, a case for each row of docs/java-subset.md.
// Tests only; nothing here is published.

dependencies {
	testImplementation(project(":oneiro-frontend-java"))
	testImplementation(project(":oneiro-lua-runner"))
	testImplementation(project(":oneiro-api"))
	testImplementation(project(":oneiro-bindings-gen"))
	testImplementation(project(":oneiro-plugin-model"))
}

tasks.test {
	// Lua runs on the bundled Lua 5.4.8 unless oneiro.luaExecutable names an installed one. StyLua checks the
	// expected files when oneiro.styluaExecutable names it. Without it the check is skipped, unless the CI
	// environment variable is set, as on most CI services: then a missing StyLua fails the suite.
	listOf("oneiro.luaExecutable", "oneiro.styluaExecutable").forEach { property ->
		providers.gradleProperty(property).orNull?.let { systemProperty(property, it) }
	}
	providers.environmentVariable("CI").orNull?.let { systemProperty("oneiro.ci", it) }
	systemProperty("oneiro.styluaConfig", rootProject.file("stylua.toml").absolutePath)
	inputs.file(rootProject.file("stylua.toml"))
	// Cases compile against bindings generated from the bindings generator's stubs fixture, so they need no checkout of
	// the stubs
	val stubsFixture = project(":oneiro-bindings-gen").file("src/test/stubs")
	systemProperty("oneiro.stubsFixture", stubsFixture.absolutePath)
	inputs.dir(stubsFixture).withPropertyName("stubsFixture")
	// BlueprintHelperTest runs Blueprint-Helper's own tests, when that repository sits next to Oneiro's
	val blueprintHelper = rootProject.file("../Blueprint-Helper")
	systemProperty("oneiro.blueprintHelper", blueprintHelper.absolutePath)
	if (blueprintHelper.resolve("tests").isDirectory) {
		inputs.dir(blueprintHelper.resolve("src")).withPropertyName("blueprintHelperSources")
		inputs.dir(blueprintHelper.resolve("tests")).withPropertyName("blueprintHelperTests")
		inputs.files(blueprintHelper.resolve("main.lua"), blueprintHelper.resolve("plugin.json"))
			.withPropertyName("blueprintHelperManifest")
	}
	// LuaJava loads its native library through System.load, which JDK 25 reports unless native access is granted
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}
