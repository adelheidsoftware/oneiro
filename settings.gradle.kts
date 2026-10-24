pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	// Downloads a Java 25 toolchain on machines that have none installed
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
	repositories {
		mavenCentral()
		// The Kotlin front end runs on the Kotlin Analysis API and the IntelliJ platform modules it needs, which
		// JetBrains publishes only to its own repositories; each is asked only for what it hosts
		maven("https://cache-redirector.jetbrains.com/intellij-dependencies") {
			content {
				includeModuleByRegex("org\\.jetbrains\\.kotlin", ".*-for-ide")
				includeGroup("com.intellij.platform")
				includeGroupByRegex("org\\.jetbrains\\.intellij\\.deps.*")
			}
		}
		maven("https://cache-redirector.jetbrains.com/intellij-repository/releases") {
			content {
				includeGroupByRegex("com\\.jetbrains\\.intellij\\..*")
			}
		}
	}
}

rootProject.name = "oneiro"

// Each published module's folder is named after its artifact, org.adelheid:<folder>, so that a build that includes
// this one resolves Oneiro's modules from it
include(
	"oneiro-api",
	"oneiro-lua-tree",
	"oneiro-lua-runner",
	"oneiro-bindings-gen",
	"oneiro-plugin-model",
	"oneiro-lowering",
	"oneiro-gradle-plugin",
	"oneiro-frontend-java",
	"oneiro-frontend-kotlin",
	"conformance",
)
