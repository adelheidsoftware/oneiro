package plugin

/** Chains of collection functions inside conditions and other expressions, run before the statement they are in. */
object Conditions {

	/** A drop: what it was, and how many. */
	data class Drop(val item: String, val count: Int)

	/** Whether a drop was rare, tested in an if. */
	fun rarity(drops: List<Drop>): String {
		if (drops.any { it.item == "rare" }) {
			return "rare"
		}
		return "common"
	}

	/** How many drops were stacks, and one more. */
	fun stacksAndOne(drops: List<Drop>): Int = drops.count { it.count > 1 } + 1

	/** Whether the wanted item dropped, when one is wanted. */
	fun dropped(drops: List<Drop>, wanted: String?): Boolean {
		return wanted != null && drops.any { it.item == wanted }
	}

	/** Whether nothing is asked, or every drop is a stack. */
	fun allStacks(drops: List<Drop>, asked: Boolean): Boolean = !asked || drops.all { it.count > 1 }

	/** The total, as text. */
	fun total(drops: List<Drop>): String = "Total: " + drops.sumOf { it.count }

	/** Whether the mood is calm, when the drops are ready. */
	fun calm(ready: Boolean, mood: Int): Boolean = ready && when (mood) {
		1 -> true
		else -> false
	}
}
