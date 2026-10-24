package plugin

import java.util.TreeMap
import java.util.TreeSet

/** Drops in the order they came, and in sorted order. */
object Ledger {

	/** The drops' names in the order they first dropped. */
	fun firstSeen(drops: List<String>): List<String> {
		val seen = mutableSetOf<String>()
		for (drop in drops) {
			seen.add(drop)
		}
		val names = ArrayList<String>()
		for (name in seen) {
			names.add(name)
		}
		return names
	}

	/** How many of each drop, in the order they first dropped, as Kotlin writes a map. */
	fun tally(drops: List<String>): String {
		val counts = mutableMapOf<String, Int>()
		for (drop in drops) {
			counts[drop] = (counts[drop] ?: 0) + 1
		}
		return counts.toString()
	}

	/** The drops still owed after the paid one, which is owed again last. */
	fun owed(drops: List<String>, paid: String): List<String> {
		val owed = mutableMapOf<String, Int>()
		for (drop in drops) {
			owed[drop] = 1
		}
		owed.remove(paid)
		owed[paid] = 1
		val order = ArrayList<String>()
		for (drop in owed.keys) {
			order.add(drop)
		}
		return order
	}

	/** The drops in alphabetical order, each once, as Kotlin writes a set. */
	fun sorted(drops: List<String>): String {
		val names = TreeSet<String>()
		for (drop in drops) {
			names.add(drop)
		}
		return names.toString()
	}

	/** The drops by name, last first, with how many of each. */
	fun reversed(counts: Map<String, Int>): List<String> {
		val sorted = TreeMap<String, Int>(reverseOrder())
		counts.forEach { (name, count) -> sorted[name] = count }
		val lines = ArrayList<String>()
		for ((key, value) in sorted) {
			lines.add("$key x$value")
		}
		return lines
	}

	/** The first and last drop by name, such as "ashes..coins". */
	fun ends(drops: List<String>): String {
		val names = TreeSet(drops)
		return "${names.first()}..${names.last()}"
	}

	/** Every drop of both runs, each once, by name. */
	fun merged(first: List<String>, second: Set<String>): List<String> {
		val all = HashSet(second)
		all.addAll(first)
		val names = ArrayList(all)
		names.sort()
		return names
	}

	/** The counts of both runs added up, sorted by name. */
	fun combined(first: Map<String, Int>, second: Map<String, Int>): Map<String, Int> {
		val total = TreeMap(first)
		second.forEach { (drop, count) -> total[drop] = (total[drop] ?: 0) + count }
		return total
	}

	/** The stock with the drops' counts put over it. */
	fun restocked(stock: Map<String, Int>, drops: Map<String, Int>): Map<String, Int> {
		val result = HashMap(stock)
		result.putAll(drops)
		return result
	}

	/** The drops of either run, each once. */
	fun either(first: Set<String>, second: Set<String>): Set<String> {
		val all = HashSet<String>()
		all.addAll(first)
		all.addAll(second)
		return all
	}

	/** The names the stock has, then the labels of the drops, in one list. */
	fun named(stock: Set<String>, labels: Map<Int, String>): List<String> {
		val names = ArrayList<String>()
		names.addAll(stock)
		names.addAll(labels.values)
		return names
	}

	/** The first and the last drop, such as "coins to bones". */
	fun firstAndLast(drops: List<String>): String {
		return "${drops.first()} to ${drops.last()}"
	}
}
