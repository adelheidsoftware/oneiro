package org.adelheid.oneiro.lowering;

/**
 * The dev source set, compiled beside the main one: it reads the main set's sources for what their classes are and
 * lowers only its own. What the main set's Lua makes of a class, its own code decides, so code of the dev set cannot
 * change it.
 */
public final class DevSourceSet {

	private DevSourceSet() {
	}

	/** Why code of the dev source set cannot read the order of an enum whose constants the main set makes strings. */
	public static String enumOrder(String enumName) {
		return "In the main source set's Lua, " + enumName + "'s constants are the strings of their names, which have"
				+ " no order, and code of the dev source set cannot change that; read its order in the main source set"
				+ " too, or give the enum a property, and its constants become objects with an ordinal.";
	}

	/**
	 * Why code of the dev source set cannot create or extend a class whose Lua, in the main set, is a module of its
	 * static members, without objects.
	 */
	public static String classObjects(String className) {
		return "In the main source set's Lua, " + className + " is a table of its static members, which has no"
				+ " objects, and code of the dev source set cannot change that; create one in the main source set too,"
				+ " or give the class an instance field or method.";
	}
}
