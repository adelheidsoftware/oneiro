package plugin

/** Herb runs, summed up with Kotlin's collection functions. */
object Herbs {

	/** A patch's herb and how many it gave. */
	data class Harvest(val herb: String, val count: Int)

	/** The herbs of the harvests that gave at least the minimum, in upper case. */
	fun good(harvests: List<Harvest>, minimum: Int): List<String> {
		val herbs = harvests
			.filter { harvest -> harvest.count >= minimum }
			.map { harvest -> harvest.herb.uppercase() }
		return herbs
	}

	/** How many harvests gave nothing. */
	fun empty(harvests: List<Harvest>): Int {
		return harvests.count { harvest -> harvest.count == 0 }
	}

	/** Whether any harvest was of the herb. */
	fun grew(harvests: List<Harvest>, herb: String): Boolean {
		return harvests.any { harvest -> harvest.herb == herb }
	}

	/** Whether every harvest gave something. */
	fun allGave(harvests: List<Harvest>): Boolean {
		val gave = harvests.all { harvest -> harvest.count > 0 }
		return gave
	}

	/** The first herb with the prefix, or "none". */
	fun firstWith(herbs: List<String>, prefix: String): String {
		return herbs.firstOrNull { herb -> herb.startsWith(prefix) } ?: "none"
	}

	/** The herbs picked in all. */
	fun total(harvests: List<Harvest>): Int {
		return harvests.sumOf { harvest -> harvest.count }
	}

	/** The biggest harvest's count, or 0 when there were none. */
	fun best(harvests: List<Harvest>): Int {
		return harvests.maxOfOrNull { harvest -> harvest.count } ?: 0
	}

	/** The herbs as one line in lower case, such as "guam, marrentill". */
	fun line(herbs: List<String>): String {
		return herbs.joinToString(", ") { herb -> herb.lowercase() }
	}

	/** Counts down from the number, into the list. */
	fun countdown(from: Int, numbers: MutableList<Int>) {
		(1..from).forEach { i -> numbers.add(from - i + 1) }
	}

	/** The harvests from the most to the least picked. */
	fun ranked(harvests: List<Harvest>): List<Harvest> {
		return harvests.sortedByDescending { harvest -> harvest.count }
	}

	/** The herbs the stock has any of. */
	fun stocked(stock: Map<String, Int>): Set<String> {
		val herbs = stock.keys.filter { herb -> stock.getValue(herb) > 0 }.toSet()
		return herbs
	}

	/** Sorts the harvests by herb name, last first. */
	fun byName(harvests: MutableList<Harvest>) {
		harvests.sortByDescending { harvest -> harvest.herb }
	}

	/** Sorts the harvests by herb, then by count. */
	fun byHerbThenCount(harvests: MutableList<Harvest>) {
		harvests.sortWith(compareBy<Harvest> { harvest -> harvest.herb }.thenBy { harvest -> harvest.count })
	}

	/** Whether a herb's name is long. */
	private fun isLong(herb: String): Boolean {
		return herb.length > 6
	}

	/** The herbs with long names. */
	fun longNames(herbs: List<String>): List<String> {
		return herbs.filter(::isLong)
	}
}
