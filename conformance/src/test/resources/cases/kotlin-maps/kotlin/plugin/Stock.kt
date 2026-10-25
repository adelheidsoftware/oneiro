package plugin

/** Chains over a map's entries, and loops that read an entry's key and value. */
object Stock {

	/** The items there are more of than the least. */
	fun plenty(stock: Map<String, Int>, least: Int): List<String> {
		return stock.filter { (_, count) -> count > least }.map { (item, _) -> item }
	}

	/** The items there are any of, in a map. */
	fun inStock(stock: Map<String, Int>): Map<String, Int> {
		return stock.filter { it.value > 0 }
	}

	/** How many items there are more than one of. */
	fun stacks(stock: Map<String, Int>): Int {
		return stock.count { it.value > 1 }
	}

	/** The item there is most of, or null for none. */
	fun mostCommon(stock: Map<String, Int>): String? {
		return stock.maxByOrNull { it.value }?.key
	}

	/** How many there are of the item there is least of, or 0 for none. */
	fun fewest(stock: Map<String, Int>): Int {
		val fewest = stock.minByOrNull { (_, count) -> count }?.value ?: 0
		return fewest
	}

	/** The first item there are as many of as wanted, or null for none. */
	fun firstWith(stock: Map<String, Int>, wanted: Int): String? {
		return stock.entries.firstOrNull { it.value == wanted }?.key
	}

	/** What each item's stack is worth. */
	fun worths(stock: Map<String, Int>, price: Int): Map<String, Int> {
		return stock.mapValues { (_, count) -> count * price }
	}

	/** The stock with its items' names in capitals. */
	fun shouted(stock: Map<String, Int>): Map<String, Int> {
		return stock.mapKeys { it.key.uppercase() }
	}

	/** The items with long names, of which there are more than two. */
	fun longNames(stock: Map<String, Int>): Map<String, Int> {
		return stock.filterKeys { it.length > 4 }.filterValues { it > 2 }
	}

	/** The stock as lines, "item: count". */
	fun lines(stock: Map<String, Int>): String {
		return stock.entries.joinToString("\n") { "${it.key}: ${it.value}" }
	}

	/** How many there are of all the items together. */
	fun total(stock: Map<String, Int>): Int {
		var total = 0
		for (entry in stock) {
			total += entry.value
		}
		return total
	}

	/** The items' names. */
	fun items(stock: Map<String, Int>): List<String> {
		val items = mutableListOf<String>()
		stock.forEach { items.add(it.key) }
		return items
	}
}
