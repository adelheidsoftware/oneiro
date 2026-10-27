package plugin

/** Kotlin's secondary constructors, and extension properties, as Lua functions. */
object Members {

	/** A tally, which starts from a value or from none. */
	class Tally(val name: String, var total: Int) {

		/** A tally that starts from none. */
		constructor(name: String) : this(name, 0)

		/** Adds the amount. */
		fun add(amount: Int) {
			total += amount
		}
	}

	/** A counter made by its one secondary constructor. */
	class Counter {

		/** How many so far. */
		var count: Int

		/** A counter from twice the start. */
		constructor(start: Int) {
			count = start * 2
		}
	}

	/** A tally of the name with nothing in it. */
	fun empty(name: String): Tally = Tally(name)

	/** A counter from three. */
	fun counter(): Counter = Counter(3)

	/** The first letter of a text. */
	val String.initial: String
		get() = take(1)

	/** Whether a list holds more than one element. */
	private val List<Int>.many: Boolean
		get() {
			return size > 1
		}

	/** The initial of a name. */
	fun initialOf(name: String): String = name.initial

	/** Whether the levels are many. */
	fun hasMany(levels: List<Int>): Boolean = levels.many
}
