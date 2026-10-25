package plugin

import org.adelheid.oneiro.rs3.Globals.log

/** What Kotlin's own constructs cannot do in Lua, each refused with the reason. */
object Refused {

	/** Names in pairs, which a class's property works out in a way Lua cannot yet. */
	class Pairs(names: List<String>) {
		private val pairs = names.chunked(2)
	}

	/** A running total. */
	class Tally(var total: Int) {

		/** An extension function inside a class with objects. */
		fun String.tagged(): String = this + total
	}

	/** A flag that must be known, where Lua's or would fail a false too. */
	fun flag(setting: Boolean?): String = "flag " + requireNotNull(setting)

	/** A flag that must be known, with ?: throw, where Lua's or would fail a false too. */
	fun known(setting: Boolean?): String = "known " + (setting ?: throw IllegalStateException("unknown"))

	/** The entries with stock, kept as entries. */
	fun entries(stock: Map<String, Int>): List<Map.Entry<String, Int>> = stock.entries.filter { it.value > 0 }

	/** The best entry, whole. */
	fun best(stock: Map<String, Int>): Map.Entry<String, Int>? = stock.maxByOrNull { it.value }

	/** Each entry, logged as it is. */
	fun logAll(stock: Map<String, Int>) {
		stock.forEach { log(it) }
	}

	/** Each item and the length of its name, as pairs made another way. */
	fun lengths(items: List<String>): Map<String, Int> = items.associate { Pair(it, it.length) }

	/** A number as text, by toString() on the receiver without naming it. */
	fun Int.label(): String = toString()

	/** A scope function inside an expression, whose code runs as statements. */
	fun logged(text: String): Int = text.apply { log(this) }.length

	/** A labelled return inside let. */
	fun shown(text: String): String {
		val shown = text.let {
			if (it.isEmpty()) {
				return@let "empty"
			}
			it
		}
		return shown
	}

	/** A when that runs only when the && gets to it. */
	fun calm(ready: Boolean, mood: Int): Boolean = ready && when (mood) {
		1 -> true
		else -> false
	}

	/** The item there is most of, by a comparator, which a map's entries do not take yet. */
	fun most(stock: Map<String, Int>): String? = stock.maxWithOrNull(compareBy { it.value })?.key
}
