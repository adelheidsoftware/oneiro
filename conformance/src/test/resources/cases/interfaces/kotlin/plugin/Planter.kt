package plugin

/** Plants each herb it is given, keeping them in order. */
class Planter : (String) -> Unit {

	private val planted = ArrayList<String>()

	override fun invoke(herb: String) {
		planted.add(herb)
	}

	fun all(): List<String> {
		return planted
	}
}
