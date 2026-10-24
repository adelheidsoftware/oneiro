// What lowering a plugin to Lua is apart from the language it is written in: the Lua modules and diagnostics a
// compile produces, and the facts about the plugin it checks the code against. The Java and Kotlin front ends both
// build on it.

plugins {
	`java-library`
}

description = "The parts of Oneiro's compiler that its Java and Kotlin front ends share."

dependencies {
	api(project(":oneiro-lua-tree"))
}
