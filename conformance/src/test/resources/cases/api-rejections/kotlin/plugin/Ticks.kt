package plugin

import org.adelheid.oneiro.rs3.Event

/** An event that passes nothing, whose payload a lambda tries to read. */
object Ticks {

	fun watch() {
		Event.ServerLogic.Subscribe("ticks.tick") { tick -> println(tick) }
	}
}
