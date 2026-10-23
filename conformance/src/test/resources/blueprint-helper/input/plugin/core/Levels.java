package plugin.core;

import org.adelheid.oneiro.api.LuaModule;

/** The six optimisation levels of a Discovery arrangement, from Blueprint-Helper's hand-written levels.lua. */
@LuaModule("src/core/levels")
public final class Levels {

	private Levels() {
	}

	/**
	 * @param penalty the sum over slots of |module rank - slot rank|
	 * @return level: 0 (Perfect) to 5 (Poor)
	 */
	public static native int fromPenalty(int penalty);
}
