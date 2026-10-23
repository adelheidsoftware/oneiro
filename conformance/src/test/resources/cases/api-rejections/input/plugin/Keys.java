package plugin;

import java.util.Map;
import java.util.Set;
import org.adelheid.oneiro.rs3.InvType;
import org.adelheid.oneiro.rs3.NPCEntity;
import org.adelheid.oneiro.rs3.NPCType;
import org.adelheid.oneiro.rs3.config;

/** Values the client hands out, used as map keys and set elements, which Lua compares by identity. */
public final class Keys {

	private Keys() {
	}

	public static int kills(Map<NPCType, Integer> counts, NPCEntity npc) {
		return counts.getOrDefault(npc.config, 0);
	}

	public static void remember(Set<InvType> inventories) {
		inventories.add(config.Inv.RS3_INV);
	}

	public static Map<InvType, String> labels() {
		return Map.of(config.Inv.RS3_INV, "backpack");
	}
}
