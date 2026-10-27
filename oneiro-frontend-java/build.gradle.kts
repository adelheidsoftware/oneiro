// The Java front end: runs javac with full type information, recovers comments, checks the supported language
// (docs/java-subset.md) and lowers each class into the Lua tree. It also runs inside a plugin's own compileJava, as
// the javac plugin Oneiro, which the Gradle plugin puts on javac's processor path.

plugins {
	`java-library`
}

description = "Oneiro's Java front end, which compiles RS3 plugins written in Java to Lua 5.4 as a javac plugin."

dependencies {
	api(project(":oneiro-lua-tree"))
	api(project(":oneiro-lowering"))
	implementation(project(":oneiro-plugin-model"))
	// For constants such as LuaConstants.PREFIX, which javac copies into the code that reads them; the front end finds
	// the annotations themselves by name, in the plugin's own classpath
	compileOnly(project(":oneiro-api"))
}

tasks.javadoc {
	// The Javadoc shows the package module-info exports, the front end's API; javadoc reads the rest from the source
	// folder, to resolve the names the API uses, without documenting it
	include("module-info.java", "org/adelheid/oneiro/java/*.java")
	(options as StandardJavadocDocletOptions).addStringOption("-source-path",
		sourceSets.main.get().java.srcDirs.joinToString(File.pathSeparator))
}
