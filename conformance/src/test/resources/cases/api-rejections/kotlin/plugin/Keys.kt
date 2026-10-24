package plugin

import org.adelheid.oneiro.rs3.InvType
import org.adelheid.oneiro.rs3.NPCEntity
import org.adelheid.oneiro.rs3.NPCType
import org.adelheid.oneiro.rs3.config

/** Values the client hands out, used as map keys and set elements, which Lua compares by identity. */
object Keys {

	fun kills(counts: Map<NPCType, Int>, npc: NPCEntity): Int {
		return counts[npc.config] ?: 0
	}

	fun remember(inventories: MutableSet<InvType>) {
		inventories.add(config.Inv.RS3_INV)
	}

	fun labels(): Map<InvType, String> {
		return mapOf(config.Inv.RS3_INV to "backpack")
	}
}
