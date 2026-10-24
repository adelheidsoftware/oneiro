package plugin

import kotlin.math.abs

/** Integer division on values that can never be negative, and on values that can. */
open class Counters {

	private var ticks = 0
	private var balance = 0
	var shared = 0

	fun tick() {
		ticks++
	}

	fun spend(amount: Int) {
		balance -= amount
	}

	/** Whole minutes of ticks, at 100 a minute. */
	fun minutes(): Int {
		return ticks / 100
	}

	fun report(): String {
		val minutes = minutes()
		val hours = minutes / 60
		return "${hours}h ${minutes % 60}m"
	}

	fun halves(values: IntArray): Int {
		var total = 0
		for (value in values) {
			total += abs(value)
		}
		return total / 2
	}

	fun balanceTens(): Int {
		return balance / 10
	}

	fun sharedTens(): Int {
		return shared / 10
	}

	open fun level(): Int {
		return ticks / 1000
	}

	fun levelHalves(): Int {
		return level() / 2
	}
}
