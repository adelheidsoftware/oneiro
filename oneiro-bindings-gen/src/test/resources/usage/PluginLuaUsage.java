package com.example.herbs.core;

import com.example.herbs.Cheats;
import com.example.herbs.ui.Badge;
import org.adelheid.oneiro.rs3.ui;

/** Uses the bindings generated from the plugin Lua fixtures, from a package of the plugin's; it only has to compile. */
final class PluginLuaUsage {

	private PluginLuaUsage() {
	}

	static void all(ui.Component parent) {
		// Modules of the class's own package, without an import
		int level = Levels.fromPenalty(4) + Levels.PERFECT;
		Tally tally = new Tally(level);
		tally.add(2);
		int count = tally.count;
		// A module of another folder, with a callback of three values
		Badge.show(parent, tally, (value, at, last) -> value + "/" + at + (last ? "" : ","));
		// The dev source set's Lua, which names the main set's types
		Cheats.fill(tally);
	}
}
