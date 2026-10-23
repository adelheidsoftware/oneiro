package plugin;

import org.adelheid.oneiro.rs3.CoordGrid;
import org.adelheid.oneiro.rs3.NPCEntity;

/** A field the client hands out as a copy, changed in place, and the change made the way that works. */
public final class Copies {

	private Copies() {
	}

	public static void moveInPlace(NPCEntity npc) {
		npc.coordGrid.x = 5;
	}

	public static CoordGrid moved(NPCEntity npc) {
		CoordGrid tile = npc.coordGrid;
		tile.x = 5;
		return tile;
	}
}
