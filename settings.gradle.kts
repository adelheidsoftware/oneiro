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
	"oneiro-gradle-plugin",
	"oneiro-frontend-java",
	"conformance",
)
