/** The annotations and base classes that RS3 plugins written in Java and Kotlin compile against. */
module org.adelheid.oneiro.api {
	// JSpecify's nullness annotations, which the API carries for the code that compiles against it
	requires static transitive org.jspecify;

	exports org.adelheid.oneiro.api;
	exports org.adelheid.oneiro.api.binding;
}
