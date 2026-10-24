// What lowering a plugin to Lua is apart from the language it is written in: the Lua modules and diagnostics a
// compile produces, the facts about the plugin it checks the code against, and how the Lua is laid out and written.
// The Java and Kotlin front ends both build on it.

plugins {
	`java-library`
}

description = "The parts of Oneiro's compiler that its Java and Kotlin front ends share."

dependencies {
	api(project(":oneiro-lua-tree"))
	// The line maps the compile writes, which explainError reads
	implementation(project(":oneiro-plugin-model"))
}
