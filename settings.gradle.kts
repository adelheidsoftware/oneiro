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

include(
	"api",
	"lua-tree",
	"lua-runner",
	"bindings-gen",
	"plugin-model",
	"gradle-plugin",
	"frontend-java",
	"conformance",
)
