/**
 * An RS3 plugin apart from its code, for Oneiro: its manifest, the checks of the folder that ships, and its typed
 * resources.
 */
module org.adelheid.oneiro.plugin {
	// JSpecify's nullness annotations, which the API carries for the code that compiles against it
	requires static transitive org.jspecify;

	exports org.adelheid.oneiro.plugin;
}
