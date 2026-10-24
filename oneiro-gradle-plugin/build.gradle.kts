import net.ltgt.gradle.errorprone.errorprone

// The Gradle plugin org.adelheid.oneiro: the oneiro { } block and the tasks that turn a plugin's Java into the folder
// and zip that ship. Compiled for Java 17, the oldest JVM Gradle 9 runs on. What needs Oneiro's JDK 25 runs elsewhere:
// the compiler inside the plugin's own javac, and the bindings generator and the Lua tests in worker processes.

plugins {
	`java-gradle-plugin`
	alias(libs.plugins.plugin.publish)
}

description = "The Gradle plugin that builds RS3 plugins written in Java with Oneiro."

dependencies {
	implementation(project(":oneiro-plugin-model"))
	testImplementation(gradleTestKit())
}

gradlePlugin {
	website = "https://github.com/adelheidsoftware/oneiro"
	vcsUrl = "https://github.com/adelheidsoftware/oneiro.git"
	plugins {
		create("oneiro") {
			id = "org.adelheid.oneiro"
			implementationClass = "org.adelheid.oneiro.gradle.OneiroPlugin"
			displayName = "Oneiro"
			description = "Compiles RuneScape 3 plugins written in Java to Lua for the RS3 Client Plugin API, and " +
				"builds, checks, tests and packages them."
			tags = listOf("runescape", "rs3", "lua", "compiler", "transpiler")
		}
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 17
	options.compilerArgs.add("-Xlint:deprecation")
	options.errorprone {
		// Gradle injects its services into abstract getters and abstract classes' constructors, which these checks
		// assume no injector does
		disable("JavaxInjectOnAbstractMethod", "InjectOnConstructorOfAbstractClass")
	}
}

tasks.processResources {
	// The versions of Oneiro's own artifacts and of JSpecify that a plugin build asks for
	val versions = mapOf("version" to project.version.toString(), "jspecify" to libs.versions.jspecify.get())
	inputs.properties(versions)
	filesMatching("**/oneiro.properties") {
		expand(versions)
	}
}

tasks.test {
	// The functional test builds a plugin as its author would, resolving Oneiro from the repository the published
	// modules go to, against the bindings generator's stubs fixture
	val publishedModules = listOf("api", "lua-tree", "lua-runner", "bindings-gen", "plugin-model", "lowering",
		"frontend-java", "gradle-plugin").map { ":oneiro-$it" }
	dependsOn(publishedModules.map { "$it:publishAllPublicationsToFunctionalTestRepository" })
	// The published modules are what the test builds with, so a change to any of them must rerun it
	inputs.files(publishedModules.map { project(it).tasks.named("jar") }).withPropertyName("publishedJars")
	systemProperty("oneiro.repository",
		rootProject.layout.buildDirectory.dir("functional-test-repository").get().asFile.absolutePath)
	systemProperty("oneiro.version", project.version.toString())
	val stubsFixture = project(":oneiro-bindings-gen").file("src/test/stubs")
	systemProperty("oneiro.stubsFixture", stubsFixture.absolutePath)
	inputs.dir(stubsFixture).withPropertyName("stubsFixture")
}
