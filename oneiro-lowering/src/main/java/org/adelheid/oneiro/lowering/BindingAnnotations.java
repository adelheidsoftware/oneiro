package org.adelheid.oneiro.lowering;

import java.util.Set;

/**
 * The annotations of {@code oneiro-api} that tell the compiler how a class meets Lua it does not write, which only
 * generated bindings carry: the RS3 bindings, the bindings of the plugin's own Lua, and the plugin's {@code Resources}
 * and {@code Urls}. The front ends reject them on the plugin's own classes, and with them a method without a body
 * that stands for Lua, Java's {@code native} and Kotlin's {@code external}: the Lua the plugin calls is bound from its
 * LuaCATS annotations instead, so that what Java and Kotlin see of it is what it says of itself.
 */
public final class BindingAnnotations {

	private static final String PACKAGE = "org.adelheid.oneiro.api";

	private static final Set<String> NAMES = Set.of("LuaColon", "LuaConstants", "LuaCopy", "LuaDevOnly", "LuaDot",
			"LuaEvent", "LuaFromId", "LuaGlobal", "LuaIndex", "LuaManifestFlag", "LuaModule", "LuaOperator",
			"LuaOptional", "LuaPermission", "LuaReview", "LuaSame", "LuaType");

	private static final String OWN_LUA = "keep the Lua the plugin calls under src/main/lua, with LuaCATS annotations"
			+ " such as ---@param and ---@return, and call the class Oneiro generates from them.";

	private BindingAnnotations() {
	}

	/** Whether an annotation, by its qualified name, is one that only bindings carry. */
	public static boolean isBindingAnnotation(String qualifiedName) {
		return qualifiedName.startsWith(PACKAGE + ".") && NAMES.contains(qualifiedName.substring(PACKAGE.length() + 1));
	}

	/** Why a class of the plugin's own may not carry a binding annotation, by its simple name. */
	public static String rejection(String annotation) {
		return "@" + annotation + " belongs to the bindings Oneiro generates, not to the plugin's own classes; "
				+ OWN_LUA;
	}

	/**
	 * Why a declaration without a body may not stand for Lua.
	 *
	 * @param declaration what the source declared: {@code A native method} or {@code An external function}
	 */
	public static String noBody(String declaration) {
		return declaration + " would stand for Lua that exists already, which Oneiro binds from its annotations rather"
				+ " than from a declaration; " + OWN_LUA;
	}
}
