package plugin

/** The collection functions that cut a chain short, flatten it, and group or pair up what it gives. */
object Loot {

	/** A drop: what it was and how many. */
	data class Drop(val item: String, val count: Int)

	/** A kill: the monster and what it dropped. */
	data class Kill(val monster: String, val drops: List<Drop>)

	/** The items dropped, each once, in the order they first dropped. */
	fun items(drops: List<Drop>): List<String> {
		return drops.map { it.item }.distinct()
	}

	/** The first drop of each item. */
	fun firstOfEach(drops: List<Drop>): List<Drop> {
		return drops.distinctBy { it.item }
	}

	/** The first three stacks of more than one. */
	fun firstStacks(drops: List<Drop>): List<Drop> {
		return drops.filter { it.count > 1 }.take(3)
	}

	/** The first half of the drops. */
	fun firstHalf(drops: List<Drop>): List<Drop> {
		return drops.take(drops.size / 2)
	}

	/** The items after the ones to skip. */
	fun after(drops: List<Drop>, skipped: Int): List<String> {
		return drops.drop(skipped).map { it.item }
	}

	/** The drops before the first coins. */
	fun beforeCoins(drops: List<Drop>): List<Drop> {
		return drops.takeWhile { it.item != "coins" }
	}

	/** The drops from the first coins on. */
	fun fromCoins(drops: List<Drop>): List<Drop> {
		return drops.dropWhile { it.item != "coins" }
	}

	/** Every drop of every kill. */
	fun allDrops(kills: List<Kill>): List<Drop> {
		return kills.flatMap { it.drops }
	}

	/** The first five drops of the kills, which stops looking once it has them. */
	fun firstFive(kills: List<Kill>): List<Drop> {
		return kills.flatMap { kill -> kill.drops }.take(5)
	}

	/** Whether any kill dropped the item, which stops looking once one has. */
	fun dropped(kills: List<Kill>, item: String): Boolean {
		val found = kills.flatMap { it.drops }.any { it.item == item }
		return found
	}

	/** Each number and ten times it. */
	fun tens(limit: Int): List<Int> {
		return (1..limit).flatMap { n -> listOf(n, n * 10) }
	}

	/** The drops of each item, in the order the items first dropped. */
	fun byItem(drops: List<Drop>): Map<String, List<Drop>> {
		return drops.groupBy { it.item }
	}

	/** The counts of each item's drops. */
	fun countsByItem(drops: List<Drop>): Map<String, List<Int>> {
		return drops.groupBy({ it.item }, { it.count })
	}

	/** Each item and its count, the last drop's for an item that dropped more than once. */
	fun counts(drops: List<Drop>): Map<String, Int> {
		return drops.associate { it.item to it.count }
	}

	/** Each kill by its monster. */
	fun byMonster(kills: List<Kill>): Map<String, Kill> {
		return kills.associateBy { it.monster }
	}

	/** Each item and the length of its name. */
	fun nameLengths(items: List<String>): Map<String, Int> {
		return items.associateWith { it.length }
	}
}
