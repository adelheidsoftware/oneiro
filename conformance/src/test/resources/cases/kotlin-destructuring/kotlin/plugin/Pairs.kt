package plugin

/** Kotlin's pairs and destructuring: of pairs, data classes and lists, partition, and the positions of a list. */
object Pairs {

	/** A drop: what it was, and how many. */
	data class Drop(val item: String, val count: Int)

	/** A name and its level, as a pair. */
	fun entry(name: String, level: Int): Pair<String, Int> = name to level

	/** A name and its level, as text. */
	fun describe(entry: Pair<String, Int>): String = entry.first + " " + entry.second

	/** The lower and the higher of two levels. */
	fun ordered(a: Int, b: Int): Pair<Int, Int> = if (a < b) Pair(a, b) else Pair(b, a)

	/** The level of a pair, read by destructuring. */
	fun level(entry: Pair<String, Int>): Int {
		val (_, level) = entry
		return level
	}

	/** The difference of two levels, from a pair made in place. */
	fun difference(low: Int, high: Int): Int {
		val (first, second) = high to low
		return first - second
	}

	/** The gap between two levels, whichever is higher. */
	fun gap(a: Int, b: Int): Int {
		val (low, high) = ordered(a, b)
		return high - low
	}

	/** A drop as text. */
	fun label(drop: Drop): String {
		val (item, count) = drop
		return "$count x $item"
	}

	/** The first two names of a list. */
	fun firstTwo(names: List<String>): String {
		val (first, second) = names
		return "$first, $second"
	}

	/** How many stacks and single drops there were, as text. */
	fun split(drops: List<Drop>): String {
		val (stacks, singles) = drops.partition { it.count > 1 }
		return "${stacks.size} stacks, ${singles.size} singles"
	}

	/** Each name with its level, as lines. */
	fun lines(entries: List<Pair<String, Int>>): String {
		var text = ""
		for ((name, level) in entries) {
			text += "$name: $level\n"
		}
		return text
	}

	/** The total count of the drops of the item. */
	fun countOf(drops: List<Drop>, wanted: String): Int {
		var total = 0
		for ((item, count) in drops) {
			if (item == wanted) {
				total += count
			}
		}
		return total
	}

	/** The names, numbered from 1. */
	fun numbered(names: List<String>): String {
		var text = ""
		for ((index, name) in names.withIndex()) {
			text += "${index + 1}. $name "
		}
		return text
	}

	/** The names before the position. */
	fun before(names: List<String>, position: Int): List<String> {
		val kept = mutableListOf<String>()
		for ((index, name) in names.withIndex()) {
			if (index < position) {
				kept.add(name)
			}
		}
		return kept
	}

	/** Each name with the level at its position. */
	fun withLevels(names: List<String>, levels: List<Int>): String {
		var text = ""
		names.forEachIndexed { index, name ->
			text += "$name ${levels[index]};"
		}
		return text
	}

	/** The names, each after its position counted from 0. */
	fun positions(names: List<String>): List<String> = names.mapIndexed { index, name -> "$index=$name" }

	/** Each drop's count by its item. */
	fun counts(drops: List<Drop>): Map<String, Int> = drops.map { it.item to it.count }.toMap()

	/** The levels by name. */
	fun levels(entries: List<Pair<String, Int>>): Map<String, Int> = entries.toMap()
}
