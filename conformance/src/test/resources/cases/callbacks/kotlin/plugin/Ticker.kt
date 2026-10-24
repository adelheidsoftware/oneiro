package plugin

import org.adelheid.oneiro.rs3.Event
import org.adelheid.oneiro.rs3.InventoryUpdatedEventPayload
import org.adelheid.oneiro.rs3.LogicEventPayload

/** An object whose methods are subscribed as callbacks. */
class Ticker {

	private var ticks = 0
	private val slots = ArrayList<Int>()

	private fun remember(event: InventoryUpdatedEventPayload) {
		for (slot in event.slotsUpdated) {
			slots.add(slot)
		}
	}

	fun start() {
		Event.Logic.Subscribe("ticker.logic", ::tick)
		Event.InventoryUpdated.Subscribe("ticker.inventory", this::remember)
	}

	fun stop() {
		Event.Logic.Unsubscribe("ticker.logic")
		Event.InventoryUpdated.Unsubscribe("ticker.inventory")
	}

	fun tick(event: LogicEventPayload) {
		ticks++
	}

	fun tickCount(): Int {
		return ticks
	}

	fun rememberedSlots(): List<Int> {
		return slots
	}

	/**
	 * Runs the action for each slot, and stops at the first that fails.
	 *
	 * @return how many succeeded
	 */
	fun each(action: (Int) -> Unit): Int {
		var done = 0
		for (slot in slots) {
			try {
				action(slot)
				done++
			} catch (e: RuntimeException) {
				return done
			}
		}
		return done
	}
}
