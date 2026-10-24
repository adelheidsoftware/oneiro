package plugin

import org.adelheid.oneiro.api.Subscribe
import org.adelheid.oneiro.rs3.InventoryUpdatedEventPayload

/** Uses the plugin class from another file, which Lua cannot. */
class Helper {

	fun count(): Int {
		return 0
	}

	fun remember(plugin: BadPlugin) {
	}

	fun make() {
		val another = BadPlugin()
	}

	@Subscribe
	fun onInventory(event: InventoryUpdatedEventPayload) {
	}
}
