package org.adelheid.oneiro.bindings;

/**
 * What a set of bindings describes: the RS3 API, from the stubs; a Lua library, from its LuaCATS types; or the plugin's
 * own Lua, from its annotations. It decides the package the classes go in, how their files say where they come from,
 * and whether the parts that only the RS3 API has are written: its events and their payloads, the hooks, the constant
 * namespaces, {@code FromID} on enum tables, {@code Globals} and the event subscription types.
 *
 * @param packageName the package of the classes; for the plugin's own Lua, its root package, below which each file's
 *     folder is a package
 * @param library the library's name, or the plugin source set's, {@code main} or {@code dev}; empty for the RS3 API
 */
record BindingsTarget(Kind kind, String packageName, String library) {

	/** The package of the RS3 bindings. */
	static final String RS3_PACKAGE = "org.adelheid.oneiro.rs3";

	/** Whose Lua the bindings stand for. */
	enum Kind {
		RS3,
		LIBRARY,
		PLUGIN,
	}

	static BindingsTarget rs3() {
		return new BindingsTarget(Kind.RS3, RS3_PACKAGE, "");
	}

	static BindingsTarget library(String name, String packageName) {
		return new BindingsTarget(Kind.LIBRARY, packageName, name);
	}

	static BindingsTarget plugin(String sourceSet, String rootPackage) {
		return new BindingsTarget(Kind.PLUGIN, rootPackage, sourceSet);
	}

	boolean isRs3() {
		return kind == Kind.RS3;
	}

	boolean isPlugin() {
		return kind == Kind.PLUGIN;
	}

	/**
	 * The package of a top-level class declared in a file: for the plugin's own Lua, the root package and the file's
	 * folders, so that {@code core/levels.lua} is in {@code <root>.core}, as a class of the plugin's own there would
	 * be; otherwise the one package.
	 *
	 * @param file the file's path in the folder of Lua files, such as {@code core/levels.lua}
	 */
	String packageOf(String file) {
		int slash = file.lastIndexOf('/');
		if (!isPlugin() || slash < 0) {
			return packageName;
		}
		return packageName + "." + file.substring(0, slash).replace('/', '.');
	}

	/** Where the bindings come from, as their files' first line says. */
	String origin() {
		return switch (kind) {
			case RS3 -> "runescape-api-stubs";
			case LIBRARY -> "the LuaCATS types of " + library;
			case PLUGIN -> "the plugin's Lua";
		};
	}

	/** What hands out the values the bindings type, as their Javadoc says: the client, a library, the plugin's Lua. */
	String provider() {
		return switch (kind) {
			case RS3 -> "the client";
			case LIBRARY -> "the library";
			case PLUGIN -> "the plugin's Lua";
		};
	}
}
