/** Oneiro's Java front end, which compiles RS3 plugins written in Java to Lua 5.4 as a javac plugin. */
module org.adelheid.oneiro.java {
	// JSpecify's nullness annotations, which the API carries for the code that compiles against it
	requires static transitive org.jspecify;
	requires transitive org.adelheid.oneiro.lua;
	requires transitive org.adelheid.oneiro.lowering;
	requires org.adelheid.oneiro.plugin;
	// For constants such as LuaConstants.PREFIX, which javac copies into the code that reads them; the front end finds
	// the annotations themselves by name, in the plugin's own classpath
	requires static org.adelheid.oneiro.api;
	requires jdk.compiler;

	exports org.adelheid.oneiro.java;

	// The javac plugin Oneiro, for a module path; on the processor path, where the Gradle plugin puts it, javac finds
	// it through META-INF/services instead
	provides com.sun.source.util.Plugin with org.adelheid.oneiro.java.OneiroJavacPlugin;
}
