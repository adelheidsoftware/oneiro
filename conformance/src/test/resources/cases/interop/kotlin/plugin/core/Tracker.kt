package plugin.core

import org.adelheid.oneiro.api.LuaName

/** Interop with existing Lua: names it calls, tables it passes, and a hand-written module it provides. */
class Tracker {

	/** One board position, as the hand-written reader reports it. */
	data class Position(val spriteId: Int?, val visible: Boolean)

	/** What the hand-written view reads. */
	data class Summary(val seen: Int, val level: Int)

	var seen = 0

	@LuaName("Ingest")
	fun ingest(positions: List<Position>) {
		for (position in positions) {
			val sprite = position.spriteId
			if (sprite != null && position.visible) {
				seen++
			}
		}
	}

	@LuaName("Summary")
	fun summary(): Summary {
		return Summary(seen, Levels.fromPenalty(seen) + Levels.PERFECT)
	}
}
