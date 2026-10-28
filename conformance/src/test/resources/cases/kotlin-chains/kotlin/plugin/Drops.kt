package plugin

import org.adelheid.oneiro.rs3.Globals.log

/** Drops summed up with the collection functions Kotlin has beyond Java's streams. */
object Drops {

	/** A drop: what it was, how many, and what one is worth. */
	data class Drop(val item: String, val count: Int, val price: Double)

	/** The items that were not coins. */
	fun items(drops: List<Drop>): List<String> {
		return drops.filterNot { it.item == "coins" }.map { it.item }
	}

	/** What each wanted drop is worth, leaving out the rest. */
	fun worths(drops: List<Drop>, wanted: Set<String>): List<Double> {
		return drops.mapNotNull { drop -> if (drop.item in wanted) drop.count * drop.price else null }
	}

	/** What the drops are worth together. */
	fun worth(drops: List<Drop>): Double {
		return drops.sumOf { it.count * it.price }
	}

	/** The drop worth the most, or null for none. */
	fun best(drops: List<Drop>): Drop? {
		return drops.maxByOrNull { it.count * it.price }
	}

	/** The smallest stack. */
	fun smallest(drops: List<Drop>): Int {
		return drops.minOf { it.count }
	}

	/** The biggest stack by a comparator. */
	fun biggest(drops: List<Drop>): Drop? {
		return drops.maxWithOrNull(compareBy { it.count })
	}

	/** The first drop of the item, or null. */
	fun find(drops: List<Drop>, item: String): Drop? {
		return drops.find { it.item == item }
	}

	/** The last drop worth more than the price. */
	fun lastWorth(drops: List<Drop>, price: Double): Drop? {
		val last = drops.lastOrNull { it.price > price }
		return last
	}

	/** Whether no drop was of the item. */
	fun missed(drops: List<Drop>, item: String): Boolean {
		return drops.none { it.item == item }
	}

	/** How many drops were stacks. */
	fun stacks(drops: List<Drop>): Int {
		return drops.filter { it.count > 1 }.count()
	}

	/** The items as one line, such as "[bones, ashes]". */
	fun line(drops: List<Drop>): String {
		return drops.joinToString(", ", "[", "]") { it.item }
	}

	/** The counts as one line, such as "3 + 1". */
	fun counts(drops: List<Drop>): String {
		return drops.joinToString(" + ") { it.count.toString() }
	}

	/** The items that dropped, each once, in the order they first dropped. */
	fun kinds(drops: List<Drop>): Set<String> {
		return drops.map { it.item }.toSet()
	}

	/** The items by name, last first. */
	fun names(drops: List<Drop>): List<String> {
		return drops.map { it.item }.sortedDescending()
	}

	/** The drops from the most to the least valuable, ties by name. */
	fun ranked(drops: List<Drop>): List<Drop> {
		return drops.sortedWith(compareByDescending<Drop> { it.count * it.price }.thenBy { it.item })
	}

	/** The stacks, by item. */
	fun stacksByItem(drops: List<Drop>): List<Drop> {
		return drops.filter { it.count > 1 }.sortedBy { it.item }
	}

	/** Sorts the drops by count, the biggest stack first. */
	fun sortByCount(drops: MutableList<Drop>) {
		drops.sortByDescending { it.count }
	}

	/** The squares of the numbers up to the limit. */
	fun squares(limit: Int): List<Int> {
		return (1..limit).map { it * it }
	}

	/** The numbers from the start down to 1 that are above the floor. */
	fun above(start: Int, floor: Int): List<Int> {
		return (start downTo 1).filter { it > floor }
	}

	/** How many the stock holds in all. */
	fun stocked(stock: Map<String, Int>): Int {
		return stock.values.sum()
	}

	/** The stocked items whose names are longer than the limit. */
	fun longNames(stock: Map<String, Int>, limit: Int): List<String> {
		return stock.keys.filter { it.length > limit }
	}

	/** What the drops worth more than the limit are worth. */
	fun valuable(drops: List<Drop>, limit: Double): List<Double> {
		return drops.map { it.count * it.price }.filter { value -> value > limit }
	}

	/** Logs each drop worth more than the price. */
	fun logWorth(drops: List<Drop>, price: Double) {
		drops.filter { it.price > price }.forEach { log("worth it:", it.item) }
	}

	/** The first item worth more than the price, or "nothing". */
	fun firstWorth(drops: List<Drop>, price: Double): String {
		val found = drops.firstOrNull { it.price > price } ?: return "nothing"
		return found.item
	}

	/** The biggest stack of the item, or 0, kept in a variable first. */
	fun mostOf(drops: List<Drop>, item: String): Int {
		var most = 0
		most = drops.filter { it.item == item }.maxOfOrNull { it.count } ?: 0
		return most
	}

	/** How many drops worthMore has looked at. */
	var looks = 0

	/** Whether the drop is worth more than the price, counted in looks. */
	fun worthMore(drop: Drop, price: Double): Boolean {
		looks++
		return drop.price > price
	}

	/** The two most valuable drops: the drops sorted, then the first two taken. */
	fun mostValuable(drops: List<Drop>): List<Drop> = drops.sortedByDescending { it.price }.take(2)

	/** The items of the stacks, from the smallest up. */
	fun stackItems(drops: List<Drop>): List<String> =
		drops.filter { it.count > 1 }.sortedBy { it.count }.map { it.item }

	/** The two cheapest drops, by a comparator, kept in a local. */
	fun cheapest(drops: List<Drop>): List<Drop> {
		val cheap = drops.sortedWith(compareBy { it.price }).take(2)
		return cheap
	}

	/** Logs the items worth more than the price on one line, looking at each drop once. */
	fun logItems(drops: List<Drop>, price: Double) {
		log(drops.filter { worthMore(it, price) }.map { it.item }.joinToString(","))
	}

	/** Each drop's line, numbered from 1, worked out in steps. */
	fun numbered(drops: List<Drop>): List<String> = drops.mapIndexed { index, drop ->
		val number = index + 1
		"$number. ${drop.item}"
	}

	/** The items of the drops worth keeping, by a test in steps. */
	fun keepers(drops: List<Drop>): List<String> = drops.filter { drop ->
		val worth = drop.count * drop.price
		worth > 150.0
	}.map { it.item }

	/** How many drops of each group are single, a chain inside a chain's lambda. */
	fun singles(groups: List<List<Drop>>): List<Int> = groups.map { group -> group.count { it.count == 1 } }

	/** The longest item name, by a reduce in steps, which leaves the first item to start from. */
	fun longest(drops: List<Drop>): String? = drops.map { it.item }.reduceOrNull { best, item ->
		val longer = item.length > best.length
		if (longer) item else best
	}
}
