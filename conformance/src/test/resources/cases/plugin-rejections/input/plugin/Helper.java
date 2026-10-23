package plugin;

import org.adelheid.oneiro.api.Subscribe;
import org.adelheid.oneiro.rs3.InventoryUpdatedEventPayload;

/** Uses the plugin class from another file, which Lua cannot. */
public class Helper {

	public int count() {
		return 0;
	}

	public void remember(BadPlugin plugin) {
	}

	public void make() {
		BadPlugin another = new BadPlugin();
	}

	@Subscribe
	public void onInventory(InventoryUpdatedEventPayload event) {
	}
}
