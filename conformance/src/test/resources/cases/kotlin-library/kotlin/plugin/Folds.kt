package plugin

/** Kotlin's collection functions that fold, flatten and build lists, and run blocks that give a value. */
object Folds {

	/** A drop: what it was, and how many. */
	data class Drop(val item: String, val count: Int)

	/** The drops' counts, added up from the start. */
	fun total(drops: List<Drop>, start: Int): Int = drops.fold(start) { sum, drop -> sum + drop.count }

	/** The biggest count, or null for none. */
	fun biggest(counts: List<Int>): Int? {
		val biggest = counts.reduceOrNull { best, count -> if (count > best) count else best }
		return biggest
	}

	/** The average count. */
	fun averageCount(drops: List<Drop>): Double = drops.map { it.count }.average()

	/** The one drop of the item, or null for none or more than one. */
	fun only(drops: List<Drop>, item: String): Drop? = drops.singleOrNull { it.item == item }

	/** Every item of every haul. */
	fun everything(hauls: List<List<String>>): List<String> = hauls.flatten()

	/** The names among values that may be anything. */
	fun names(values: List<Any>): List<String> = values.filterIsInstance<String>()

	/** The items, last first. */
	fun backwards(items: List<String>): List<String> = items.reversed()

	/** The items worth keeping: the rare one first, then every item that is not coins. */
	fun keep(items: List<String>, rare: String?): List<String> = buildList {
		if (rare != null) {
			add(rare)
		}
		for (item in items) {
			if (item != "coins") {
				add(item)
			}
		}
	}

	/** The label for a count, worked out in a block of its own. */
	fun label(count: Int): String {
		val label = run {
			val many = count > 1
			if (many) "stacks" else "one"
		}
		return label
	}

	/** Twice the count, from a block of one value. */
	fun doubled(count: Int): Int {
		val doubled = run { count * 2 }
		return doubled
	}

	/** The count after the next drop, as text. */
	fun nextCount(count: Int): String = "Next: " + run {
		val following = count + 1
		following
	}
}
