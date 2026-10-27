package plugin

/** Gaps between Kotlin and Lua, each rejected with what to write instead. */
object Gaps {

	/** A point as a plain table. */
	data class Point(val x: Int, val y: Int)

	/** An interface that classes implement. */
	fun interface Area {
		fun area(): Double
	}

	/** One class that implements it. */
	class Unit : Area {
		override fun area(): Double {
			return 1.0
		}

		private fun helper(): Int {
			return 1
		}
	}

	/** Another class with a private member of the same name. */
	class Other {
		private fun helper(): Int {
			return 2
		}
	}

	fun inside(value: Int): Int {
		return maxOf(value, 0) + when (value) {
			1 -> 10
			else -> 20
		}
	}

	fun kind(value: Any): String? {
		return value::class.simpleName
	}

	fun words(text: String): Int {
		return text.split(Regex("""\s+""")).size
	}

	fun both(ready: Boolean, values: List<Int>): Boolean {
		return ready and values.isEmpty()
	}

	fun replaced(values: MutableList<Int>): Int {
		val old = values.set(0, 1)
		return old
	}

	fun keyed(): Map<Point, Int> {
		val map = HashMap<Point, Int>()
		map[Point(1, 2)] = 3
		return map
	}

	fun nulls(names: MutableList<String?>) {
		names.add(null)
	}

	fun search(rows: List<List<Int>>): Int {
		var found = 0
		rows@ for (row in rows) {
			for (value in row) {
				if (value < 0) {
					continue@rows
				}
				found++
			}
		}
		return found
	}

	fun area(): Area {
		return Area { 2.0 }
	}

	fun general(value: Double): String {
		return "%g".format(value)
	}

	fun grouped(value: Int): String {
		return "%,d".format(value)
	}

	fun hashed(point: Point): Int {
		return point.hashCode()
	}

	fun dated(millis: Long): String {
		return "%tF".format(millis)
	}

	fun nullness(name: String?): String {
		return "%b".format(name)
	}

	fun afterBlank(text: String): Char {
		return text[text.indexOf(" ") + 1]
	}

	fun accent(): Int {
		return 'é' - 'a'
	}

	fun neighbours(names: List<String>): List<kotlin.Pair<String, String>> {
		return names.zipWithNext()
	}

	fun counted(names: List<String>): Int {
		return names.indexOf("") + names.count { it.isNotEmpty() }
	}

	fun sortedFirst(names: List<String>): List<String> {
		return names.sorted().filter { it.isNotEmpty() }
	}

	/** Herbs that a for loop could walk. */
	abstract class Basket : Iterable<String>

	/** A step that says what it is, which no class implements. */
	fun interface Step {
		fun run()

		fun describe(): String {
			return "a step"
		}
	}

	fun stepName(step: Step): String {
		return step.describe()
	}

	/** A class that extends a library class. */
	class Names : ArrayList<String>()

	fun digits(text: String): Boolean {
		return text.matches(Regex("""\d+"""))
	}
}
