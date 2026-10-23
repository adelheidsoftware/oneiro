package plugin.core;

import org.adelheid.oneiro.api.LuaModule;

/** The hand-written levels module, declared so that Java can call it. */
@LuaModule("src/core/levels")
public final class Levels {

	/** The level of a perfect arrangement. */
	public static int PERFECT;

	private Levels() {
	}

	public static native int fromPenalty(int penalty);
}
