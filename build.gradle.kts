import com.vanniktech.maven.publish.MavenPublishBaseExtension
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

// Settings every module shares: the Java 25 toolchain, JSpecify, Error Prone with NullAway, Checkstyle and JUnit, and
// the publishing of every module but the conformance suite. The group and version are in gradle.properties.

plugins {
	// Lifecycle tasks (build, check, clean) at the root, so `./gradlew build` works with or without modules
	base
	alias(libs.plugins.errorprone) apply false
	alias(libs.plugins.maven.publish) apply false
	alias(libs.plugins.plugin.publish) apply false
	alias(libs.plugins.kotlin.jvm) apply false
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
			// Unlike Checkstyle's check, this one sees a static import whose name is also a local's
			check("RemoveUnusedImports", CheckSeverity.ERROR)
		}
	}

	tasks.withType<Javadoc>().configureEach {
		(options as StandardJavadocDocletOptions).apply {
			encoding = "UTF-8"
			// Every check but the one that wants @param and @return where the description already says it; a warning
			// fails the build
			addBooleanOption("Xdoclint:all,-missing", true)
			addBooleanOption("Werror", true)
			// No web fonts, which would make each Javadoc jar 4 MB, mostly fonts, against Maven Central's monthly limit
			// on what a namespace publishes; the pages use the browser's own
			addBooleanOption("-no-fonts", true)
			quiet()
		}
	}

	// The layout rules in config/checkstyle; a violation fails the build
	apply(plugin = "checkstyle")
	extensions.configure<CheckstyleExtension> {
		toolVersion = checkstyleVersion
		configFile = rootProject.file("config/checkstyle/checkstyle.xml")
		maxWarnings = 0
	}
	// Kotlin sources follow the same layout rules. Checkstyle's syntax checks read only Java, so for Kotlin it applies
	// the rules on lines and files: their length, trailing whitespace, blank lines and the final newline
	pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
		extensions.getByType<SourceSetContainer>().configureEach {
			val kotlinSources = extensions.getByName<SourceDirectorySet>("kotlin")
			val checkstyleKotlin = tasks.register<Checkstyle>(getTaskName("checkstyle", "kotlin")) {
				source(kotlinSources)
				classpath = files()
			}
			tasks.named("check") {
				dependsOn(checkstyleKotlin)
			}
		}
	}

	tasks.withType<Test>().configureEach {
		useJUnitPlatform()
	}

	// Every jar, sources and Javadoc included, carries the licence
	tasks.withType<Jar>().configureEach {
		from(rootProject.file("LICENSE")) {
			into("META-INF")
		}
	}
}

// Every module but the conformance suite is published to Maven Central as org.adelheid:<module>, with sources and
// Javadoc; RELEASING.md has the steps. The Gradle plugin's functional test publishes them to a repository inside this
// build instead, and builds a plugin against it as a plugin author would.
val functionalTestRepository = layout.buildDirectory.dir("functional-test-repository")
subprojects {
	if (name == "conformance") {
		return@subprojects
	}
	apply(plugin = "com.vanniktech.maven.publish")
	extensions.configure<MavenPublishBaseExtension> {
		publishToMavenCentral()
		// Maven Central takes only signed artifacts, but a local repository needs no key, so sign when there is one
		if (providers.gradleProperty("signingInMemoryKey").isPresent ||
			providers.gradleProperty("signing.keyId").isPresent) {
			signAllPublications()
		}
		pom {
			name = project.name
			description = provider { project.description }
			inceptionYear = "2026"
			url = "https://github.com/adelheidsoftware/oneiro"
			licenses {
				license {
					name = "MIT License"
					url = "https://opensource.org/license/mit"
					distribution = "repo"
				}
			}
			developers {
				developer {
					id = "adelheidsoftware"
					name = "Adelheid Software"
					url = "https://github.com/adelheidsoftware"
				}
			}
			scm {
				url = "https://github.com/adelheidsoftware/oneiro"
				connection = "scm:git:https://github.com/adelheidsoftware/oneiro.git"
				developerConnection = "scm:git:ssh://git@github.com/adelheidsoftware/oneiro.git"
			}
		}
	}
	extensions.configure<PublishingExtension> {
		repositories {
			maven {
				name = "functionalTest"
				url = uri(functionalTestRepository)
			}
		}
	}
	// The repository is in this build's own folder, which its clean deletes, so in `clean build` that comes first
	tasks.withType<PublishToMavenRepository>().configureEach {
		if (name.endsWith("ToFunctionalTestRepository")) {
			mustRunAfter(":clean")
		}
	}
}
