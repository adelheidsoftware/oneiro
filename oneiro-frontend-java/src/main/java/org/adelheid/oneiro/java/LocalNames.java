package org.adelheid.oneiro.java;

import java.util.Set;
import org.adelheid.oneiro.lua.LuaNames;

/**
 * Lua names for Java locals, parameters and private members. Java names are kept, except those Lua cannot use or
 * that would hide a name the generated Lua relies on: those get a trailing underscore, so {@code end} becomes
 * {@code end_} and {@code table} becomes {@code table_}.
 */
final class LocalNames {

	/** Lua globals that generated code calls, which a local of the same name would hide. */
	private static final Set<String> GLOBALS = Set.of(
			"error", "formatStr", "getmetatable", "ipairs", "math", "next", "pairs", "pcall", "print", "require",
			"select", "setmetatable", "string", "table", "tonumber", "tostring", "type");
	/** What a method calls its object in the Lua, which a Java local of that name would hide: a hook's self. */
	private static final String SELF = "self";

	private final Set<String> taken;

	/**
	 * @param taken the names of the helper functions the module defines and of the API globals it reads, which locals
	 *     must not hide either
	 */
	LocalNames(Set<String> taken) {
		this.taken = Set.copyOf(taken);
	}

	String luaName(String javaName) {
		// Java's unnamed variable, _, has no name in the tree; Lua's convention for an unused one is _ too
		if (javaName.isEmpty()) {
			return "_";
		}
		if (LuaNames.isKeyword(javaName) || GLOBALS.contains(javaName) || javaName.equals(SELF)
				|| taken.contains(javaName)) {
			return javaName + "_";
		}
		return javaName;
	}

	/** Whether a Java name can become a Lua name at all: Lua names are ASCII letters, digits and underscores. */
	static boolean isAscii(String javaName) {
		return javaName.chars().allMatch(c -> c < 128 && (Character.isLetterOrDigit(c) || c == '_'));
	}
}
