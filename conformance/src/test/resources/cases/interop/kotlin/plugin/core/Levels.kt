package plugin.core

import org.adelheid.oneiro.api.LuaModule

/** The hand-written levels module, declared so that Kotlin can call it. */
@LuaModule("src/core/levels")
object Levels {

	/** The level of a perfect arrangement. */
	val PERFECT: Int
		external get

	external fun fromPenalty(penalty: Int): Int
}
