package plugin

import org.adelheid.oneiro.rs3.CoordGrid
import org.adelheid.oneiro.rs3.NPCEntity

/** A property the client hands out as a copy, changed in place, and the change made the way that works. */
object Copies {

	fun moveInPlace(npc: NPCEntity) {
		npc.coordGrid.x = 5
	}

	fun moved(npc: NPCEntity): CoordGrid {
		val tile = npc.coordGrid
		tile.x = 5
		return tile
	}
}
