package plugin

/** The herbs planted in a farmer's patches. */
object Patches {

	/** The herbs a farmer of the level can plant, as text. */
	fun plantable(farming: Int): String {
		val text = StringBuilder()
		for (herb in Herb.entries) {
			if (herb.canPlant(farming)) {
				text.append(herb).append(' ')
			}
		}
		return text.toString().trim()
	}

	/** How many patches grow each herb. */
	fun counts(planted: List<Herb>): Map<Herb, Int> {
		val counts = HashMap<Herb, Int>()
		for (herb in planted) {
			counts[herb] = (counts[herb] ?: 0) + 1
		}
		return counts
	}

	/** The herb a saved name stands for. */
	fun saved(name: String): Herb {
		return Herb.valueOf(name)
	}

	/** Whether the first herb comes before the second. */
	fun earlier(first: Herb, second: Herb): Boolean {
		return first < second
	}

	/** The stage's place in the growth, from 0. */
	fun step(stage: Stage): Int {
		return stage.ordinal
	}

	/** The tiers from the easiest up, in the order the enum declares them. */
	fun easiestFirst(tiers: List<Tier>): List<Tier> {
		val sorted = tiers.toMutableList()
		sorted.sort()
		return sorted
	}

	/** What a herb's patch looks like at the stage. */
	fun look(herb: Herb, stage: Stage): String {
		return when (stage) {
			Stage.SEED -> "a seed of ${herb.describe()}"
			Stage.GROWING -> "growing ${herb.name}"
			Stage.DONE -> "$herb ready"
		}
	}
}
