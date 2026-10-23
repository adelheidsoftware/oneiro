package plugin;

import static org.adelheid.oneiro.rs3.Globals.log;

import org.adelheid.oneiro.api.Plugin;
import org.adelheid.oneiro.api.Subscribe;
import org.adelheid.oneiro.rs3.EntityDrawEventPayload;
import org.adelheid.oneiro.rs3.InventoryUpdatedEventPayload;
import org.adelheid.oneiro.rs3.ServerLogicEventPayload;
import org.adelheid.oneiro.rs3.config;

/** Counts game ticks and backpack changes while the plugin runs. */
public final class HerbPlugin extends Plugin {

	private static final int REPORT_EVERY = 100;

	private HerbConfig settings;
	private final Tally tally = new Tally();
	private int ticks;
	private int nextReport = REPORT_EVERY;

	@Override
	protected void startUp() {
		// The greeting is read once, when the plugin starts
		if (settings.greet()) {
			log("Herb tracker started");
		}
	}

	@Override
	protected void shutDown() {
		settings.setRuns(settings.runs() + 1);
	}

	private void report() {
		log("Ticks:", ticks, "backpack changes:", tally.sum());
	}

	@Subscribe
	public void onServerLogic(ServerLogicEventPayload tick) {
		ticks++;
		if (ticks == nextReport) {
			report();
			nextReport += REPORT_EVERY;
		}
	}

	@Subscribe
	public void onInventoryUpdated(InventoryUpdatedEventPayload event) {
		if (event.inv == config.Inv.RS3_INV) {
			tally.add(event.slotsUpdated.size());
		}
	}

	@Subscribe
	public boolean onEntityDraw(EntityDrawEventPayload event) {
		return event.npcEntity == null || !settings.hideHans() || event.npcEntity.config != config.NPC.RS3_HANS;
	}
}
