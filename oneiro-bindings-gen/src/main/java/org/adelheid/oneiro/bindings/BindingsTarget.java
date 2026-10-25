package org.adelheid.oneiro.bindings;

/**
 * What a set of bindings describes: the RS3 API, from the stubs, or a Lua library, from its LuaCATS types. It decides
 * the package the classes go in, how their files say where they come from, and whether the parts that only the RS3
 * API has are written: its events and their payloads, the hooks, the constant namespaces, {@code FromID} on enum
 * tables, {@code Globals} and the event subscription types.
 *
 * @param library the library's name; empty for the RS3 API
 */
record BindingsTarget(String packageName, String library) {

	/** The package of the RS3 bindings. */
	static final String RS3_PACKAGE = "org.adelheid.oneiro.rs3";

	static BindingsTarget rs3() {
		return new BindingsTarget(RS3_PACKAGE, "");
	}

	static BindingsTarget library(String name, String packageName) {
		return new BindingsTarget(packageName, name);
	}

	boolean isRs3() {
		return library.isEmpty();
	}

	/** Where the bindings come from, as their files' first line says. */
	String origin() {
		return isRs3() ? "runescape-api-stubs" : "the LuaCATS types of " + library;
	}
}
