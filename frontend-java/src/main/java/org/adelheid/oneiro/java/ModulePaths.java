package org.adelheid.oneiro.java;

import java.util.Locale;

/**
 * Where a plugin class's Lua goes: packages below the plugin's root package become folders under {@code src/}, and
 * the class name becomes a snake case file name, so {@code <root>.core.OpeningTable} is
 * {@code src/core/opening_table.lua}, loaded with {@code require("src/core/opening_table")}.
 */
final class ModulePaths {

	private ModulePaths() {
	}

	/** The require path, without {@code .lua}, under {@code src/}. */
	static String requirePath(String rootPackage, String packageName, String className) {
		return requirePath("src", rootPackage, packageName, className);
	}

	/**
	 * The require path, without {@code .lua}, under a folder of the plugin: {@code src}, or {@code dev} for the dev
	 * source set, whose Lua stays out of the zip.
	 */
	static String requirePath(String folder, String rootPackage, String packageName, String className) {
		String relative;
		if (packageName.equals(rootPackage)) {
			relative = "";
		} else if (packageName.startsWith(rootPackage + ".")) {
			relative = packageName.substring(rootPackage.length() + 1).replace('.', '/') + "/";
		} else {
			throw new IllegalArgumentException(packageName + " is not inside the root package " + rootPackage);
		}
		return folder + "/" + relative + snakeCase(className);
	}

	static boolean isInside(String rootPackage, String packageName) {
		return packageName.equals(rootPackage) || packageName.startsWith(rootPackage + ".");
	}

	/**
	 * {@code OpeningTable} to {@code opening_table}; an acronym stays one word, so {@code NPCTracker} is
	 * {@code npc_tracker} and {@code XPDrop} is {@code xp_drop}.
	 */
	static String snakeCase(String className) {
		String words = className
				// A lower-case letter or digit followed by a capital starts a word: openingTable -> opening_Table
				.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
				// The last capital of an acronym followed by a lower-case letter starts a word: NPCTracker -> NPC_Tracker
				.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
		return words.toLowerCase(Locale.ROOT);
	}
}
