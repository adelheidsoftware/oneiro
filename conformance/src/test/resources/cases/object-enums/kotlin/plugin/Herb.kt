package plugin

/** Herbs a patch can grow, each with its seed and the Farming level it needs. */
enum class Herb(private val seedId: Int, private val requiredLevel: Int) {
	/** The first herb a farmer grows. */
	GUAM(5291, 9),
	MARRENTILL(5292, 14), // a little later
	RANARR(5295, 32) {
		// Only Ranarr has a note
		private val note = "the prayer herb"

		// Ranarr is known for prayer, not for its seed
		override fun describe(): String {
			return note
		}
	};

	/** Whether a farmer of the level can plant it. */
	fun canPlant(farming: Int): Boolean {
		return farming >= requiredLevel
	}

	/** What the herb is, for the log. */
	open fun describe(): String {
		return "${name.lowercase()} from seed $seedId"
	}

	/** The herb after this one, or this one when it is the last. */
	fun next(): Herb {
		val all = entries
		if (ordinal + 1 < all.size) {
			return all[ordinal + 1]
		}
		return this
	}
}
