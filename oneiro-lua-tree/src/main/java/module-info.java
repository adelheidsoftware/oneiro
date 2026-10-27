/** Oneiro's Lua syntax tree, and the printer that writes it in the style of hand-written RS3 plugins. */
module org.adelheid.oneiro.lua {
	// JSpecify's nullness annotations, which the API carries for the code that compiles against it
	requires static transitive org.jspecify;

	exports org.adelheid.oneiro.lua;
}
