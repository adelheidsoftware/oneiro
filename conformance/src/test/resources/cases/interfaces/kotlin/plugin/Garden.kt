package plugin

/** A garden that plants, sorts and describes herbs. */
object Garden {

	/** Plants the herbs one by one with a planter. */
	fun plant(herbs: List<String>): List<String> {
		val planter = Planter()
		herbs.forEach(planter)
		return planter.all()
	}

	/** The herbs from the lowest level up. */
	fun byLevel(herbs: List<Herbal>): List<Herbal> {
		val sorted = herbs.toMutableList()
		sorted.sort()
		return sorted
	}

	/** A herb's description in capitals. */
	fun shout(herb: Herbal): String {
		return Describable.shout(herb)
	}
}
