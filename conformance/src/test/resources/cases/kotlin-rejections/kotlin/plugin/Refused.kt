package plugin

import org.adelheid.oneiro.rs3.Globals.log

/** What Kotlin's own constructs cannot do in Lua, each refused with the reason. */
object Refused {

	/** Names in pairs, which a class's property works out in a way Lua cannot yet. */
	class Pairs(names: List<String>) {
		private val pairs = names.zipWithNext()
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

	/** Each item and the length of its name, as pairs a function makes. */
	fun lengths(items: List<String>, pair: (String) -> Pair<String, Int>) = items.associate { pair(it) }

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

	/** A when after a call, whose effects would then come after the branches' code. */
	fun calm(mood: Int): String = mood.toString() + when (mood) {
		1 -> " calm"
		else -> " angry"
	}

	/** The item there is most of, by a comparator, which a map's entries do not take yet. */
	fun most(stock: Map<String, Int>): String? = stock.maxWithOrNull(compareBy { it.value })?.key

	/** Whether two pairs are equal, which Kotlin decides by their parts. */
	fun same(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean = a == b

	/** The levels split in two, kept as a pair of lists. */
	fun parts(levels: List<Int>): Pair<List<Int>, List<Int>> = levels.partition { it > 1 }

	/** A tally whose secondary constructor works out the value it passes on. */
	class Doubled(val total: Int) {

		/** Twice the half, when asked. */
		constructor(half: Int, twice: Boolean) : this(if (twice) half * 2 else half)
	}

	/** A tally whose secondary constructors give one parameter two defaults. */
	class Torn(val name: String, val total: Int) {

		/** From none. */
		constructor() : this("", 0)

		/** From one. */
		constructor(name: String) : this(name, 1)
	}

	/** A class with an extension property, which its objects cannot hold. */
	class Shouter(val volume: Int) {

		/** The text, shouted. */
		val String.loud: String
			get() = uppercase()
	}

	/** A count kept for the extension property below. */
	private var kept = 0

	/** The count, through an extension property with a setter. */
	var Int.counted: Int
		get() = kept + this
		set(value) {
			kept = value
		}
}
