/** The parts of Oneiro's compiler that its Java and Kotlin front ends share. */
module org.adelheid.oneiro.lowering {
	// JSpecify's nullness annotations, which the API carries for the code that compiles against it
	requires static transitive org.jspecify;
	requires transitive org.adelheid.oneiro.lua;
	// The line maps the compile writes, which explainError reads
	requires org.adelheid.oneiro.plugin;

	exports org.adelheid.oneiro.lowering;
	exports org.adelheid.oneiro.lowering.client;
	exports org.adelheid.oneiro.lowering.translation;
}
