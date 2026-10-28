package plugin

/** Streams with more steps: limit and skip, a sort with steps after it, and lambdas of several statements. */
object Rankings {

	/** A drop: what it was, how many, and what one is worth. */
	data class Drop(val item: String, val count: Int, val price: Int)

	/** The first few items. */
	fun firstItems(drops: List<Drop>, count: Int): List<String> = drops.map { drop -> drop.item }.take(count)

	/** The items of the first half of the drops, a limit worked out once. */
	fun firstHalf(drops: List<Drop>): List<String> = drops.take(drops.size / 2).map { drop -> drop.item }

	/** The items after the first. */
	fun laterItems(drops: List<Drop>): List<String> = drops.drop(1).map { drop -> drop.item }

	/** The two most valuable drops: the drops sorted, then the first two taken. */
	fun mostValuable(drops: List<Drop>): List<Drop> = drops.sortedByDescending { it.price }.take(2)

	/** The items of the stacks, from the smallest up. */
	fun stackItems(drops: List<Drop>): List<String> =
		drops.filter { drop -> drop.count > 1 }.sortedBy { it.count }.map { drop -> drop.item }

	/** The items of the drops worth keeping, by a test in steps. */
	fun keepers(drops: List<Drop>): List<String> = drops.filter { drop ->
		val worth = drop.count * drop.price
		worth > 150
	}.map { drop -> drop.item }

	/** Each drop's line, worked out in steps. */
	fun lines(drops: List<Drop>): List<String> = drops.map { drop ->
		val count = "${drop.count}x"
		count + " " + drop.item
	}

	/** How many drops of each group are single, a stream inside a stream's lambda. */
	fun singles(groups: List<List<Drop>>): List<Int> = groups.map { group -> group.count { drop -> drop.count == 1 } }
}
