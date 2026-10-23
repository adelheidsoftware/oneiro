// The Java front end: runs javac with full type information, recovers comments, checks the supported language
// (docs/java-subset.md) and lowers each class into the Lua tree. It also runs inside a plugin's own compileJava, as
// the javac plugin Oneiro, which the Gradle plugin puts on javac's processor path.

plugins {
	`java-library`
}

dependencies {
	api(project(":lua-tree"))
	implementation(project(":plugin-model"))
	// For constants such as LuaConstants.PREFIX, which javac copies into the code that reads them; the front end finds
	// the annotations themselves by name, in the plugin's own classpath
	compileOnly(project(":api"))
}
