package plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.adelheid.oneiro.rs3.Event;
import org.adelheid.oneiro.rs3.InventoryUpdatedEventPayload;
import org.adelheid.oneiro.rs3.LogicEventPayload;

/** An object whose methods are subscribed as callbacks. */
public class Ticker {

	private int ticks;
	private final List<Integer> slots = new ArrayList<>();

	private void remember(InventoryUpdatedEventPayload event) {
		for (int slot : event.slotsUpdated) {
			slots.add(slot);
		}
	}

	public void start() {
		Event.Logic.Subscribe("ticker.logic", this::tick);
		Event.InventoryUpdated.Subscribe("ticker.inventory", this::remember);
	}

	public void stop() {
		Event.Logic.Unsubscribe("ticker.logic");
		Event.InventoryUpdated.Unsubscribe("ticker.inventory");
	}

	public void tick(LogicEventPayload event) {
		ticks++;
	}

	public int tickCount() {
		return ticks;
	}

	public List<Integer> rememberedSlots() {
		return slots;
	}

	/**
	 * Runs the action for each slot, and stops at the first that fails.
	 *
	 * @return how many succeeded
	 */
	public int each(Consumer<Integer> action) {
		int done = 0;
		for (int slot : slots) {
			try {
				action.accept(slot);
				done++;
			} catch (RuntimeException e) {
				return done;
			}
		}
		return done;
	}
}
