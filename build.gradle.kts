import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

// Root of the Oneiro build: settings every module shares. Each module applies its own `java-library` or `java`
// plugin; this file adds the Java 25 toolchain, JSpecify, Error Prone with NullAway, Checkstyle, and JUnit to all of
// them.

plugins {
	// Lifecycle tasks (build, check, clean) at the root, so `./gradlew build` works with or without modules
	base
	alias(libs.plugins.errorprone) apply false
}

// The type-safe `libs` accessor resolves against the project being configured, so inside subprojects it would target
// a module, where the catalog extension is not registered. Capture what that block needs here, in root scope.
val jspecify = libs.jspecify
val errorproneCore = libs.errorprone.core
val nullaway = libs.nullaway
val junitBom = libs.junit.bom
val junitJupiter = libs.junit.jupiter
val junitPlatformLauncher = libs.junit.platform.launcher
val checkstyleVersion = libs.versions.checkstyle.get()

allprojects {
	group = "org.adelheid"
	version = "0.1.0-SNAPSHOT"
}

subprojects {
	apply(plugin = "java")
	apply(plugin = "net.ltgt.errorprone")

	extensions.configure<JavaPluginExtension> {
		toolchain {
			languageVersion = JavaLanguageVersion.of(25)
		}
	}

	dependencies {
		// Every module marks its packages @NullMarked and its nullable types @Nullable
		"implementation"(jspecify)
		"errorprone"(errorproneCore)
		"errorprone"(nullaway)
		"testImplementation"(platform(junitBom))
		"testImplementation"(junitJupiter)
		"testRuntimeOnly"(junitPlatformLauncher)
	}

	tasks.withType<JavaCompile>().configureEach {
		options.encoding = "UTF-8"
		options.errorprone {
			// Every package is @NullMarked; NullAway turns a nullness mistake into a compile error
			check("NullAway", CheckSeverity.ERROR)
			option("NullAway:OnlyNullMarked", "true")
			option("NullAway:JSpecifyMode", "true")
		}
	}

	// The layout rules in config/checkstyle; a violation fails the build
	apply(plugin = "checkstyle")
	extensions.configure<CheckstyleExtension> {
		toolVersion = checkstyleVersion
		configFile = rootProject.file("config/checkstyle/checkstyle.xml")
		maxWarnings = 0
	}

	tasks.withType<Test>().configureEach {
		useJUnitPlatform()
	}
}

// What a plugin build uses, published to a repository inside this build for the Gradle plugin's functional test,
// which builds a plugin against it as a plugin author would. Nothing is published to Maven Central yet.
val publishedModules = listOf("api", "lua-tree", "lua-runner", "bindings-gen", "plugin-model", "frontend-java",
	"gradle-plugin")
val functionalTestRepository = layout.buildDirectory.dir("functional-test-repository")
subprojects {
	if (name in publishedModules) {
		apply(plugin = "maven-publish")
		extensions.configure<PublishingExtension> {
			repositories {
				maven {
					name = "functionalTest"
					url = uri(functionalTestRepository)
				}
			}
			// java-gradle-plugin publishes the Gradle plugin and its marker on its own
			if (name != "gradle-plugin") {
				publications {
					create<MavenPublication>("maven") {
						from(components["java"])
					}
				}
			}
		}
	}
}
