package plugin;

import static org.adelheid.oneiro.rs3.Globals.ExecuteConsoleCommand;
import static org.adelheid.oneiro.rs3.Globals.log;

import org.adelheid.oneiro.api.DevPlugin;
import org.adelheid.oneiro.api.Subscribe;
import org.adelheid.oneiro.rs3.ServerLogicEventPayload;

/** Development helpers: a teleport when the plugin starts, and the ticks it ran for when it stops. */
public final class Cheats extends DevPlugin {

	private int ticks;

	@Override
	protected void startUp() {
		ExecuteConsoleCommand("!tele 3058 3311 0");
	}

	@Override
	protected void shutDown() {
		log("Ticks while the plugin ran:", ticks);
	}

	@Subscribe
	public void onServerLogic(ServerLogicEventPayload tick) {
		ticks++;
	}
}
