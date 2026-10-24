package plugin

/** A herb that says what it is, and orders by the level it needs. */
class Herbal(private val herb: String, private val level: Int) : Describable, Comparable<Herbal> {

	override fun label(): String {
		return herb
	}

	override fun kind(): String {
		return "herb"
	}

	override fun compareTo(other: Herbal): Int {
		return level - other.level
	}
}
