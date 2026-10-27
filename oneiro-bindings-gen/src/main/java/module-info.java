/** Generates Oneiro's Java bindings of the RS3 Client Plugin API from rs3-api-types' stubs. */
module org.adelheid.oneiro.bindings {
	// JSpecify's nullness annotations, which the API carries, and which the generated sources compile against
	requires transitive org.jspecify;
	// The binding annotations, which the generated sources compile against
	requires org.adelheid.oneiro.api;
	// The facts checkPlugin reads, written next to the RS3 bindings
	requires transitive org.adelheid.oneiro.plugin;
	// javac, which compiles the generated sources
	requires java.compiler;

	exports org.adelheid.oneiro.bindings;
}
