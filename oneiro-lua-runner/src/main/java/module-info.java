/** Runs Lua 5.4 for Oneiro, on the bundled Lua 5.4.8 or an installed interpreter, and a plugin's Lua tests. */
module org.adelheid.oneiro.runner {
	// JSpecify's nullness annotations, which the API carries for the code that compiles against it
	requires static transitive org.jspecify;
	// LuaJava, whose native build of the reference implementation is the bundled Lua
	requires party.iroiro.luajava;
	requires party.iroiro.luajava.lua54;

	exports org.adelheid.oneiro.runner;
}
