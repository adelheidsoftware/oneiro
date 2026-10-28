package plugin

/** Maps and sets whose order nothing reads, which plain tables hold, beside one whose order the code reads. */
object Stock {

	private val counts = mutableMapOf<String, Int>()
	private val seen = mutableSetOf<String>()
	private val arrivals = mutableMapOf<String, Int>()
	private val prices = mapOf("guam" to 10, "ranarr" to 25)

	/** Adds some of an item. */
	fun add(item: String, amount: Int) {
		counts[item] = (counts[item] ?: 0) + amount
		seen.add(item)
		arrivals[item] = amount
	}

	/** How many of an item there are, or -1 for one never added. */
	fun count(item: String): Int = if (item in seen) counts.getOrDefault(item, 0) else -1

	/** The items in the order they first arrived. */
	fun order(): String {
		var text = ""
		for (item in arrivals.keys) {
			text += item
		}
		return text
	}

	/** What an item is worth, or 0. */
	fun price(item: String): Int = prices[item] ?: 0

	/** How many different items there are, by a map of the function's own. */
	fun kinds(items: List<String>): Int {
		val kinds = mutableMapOf<String, Boolean>()
		for (item in items) {
			kinds[item] = true
		}
		return kinds.size
	}
}
