package plugin

/** Extension functions, which take their receiver first, as self. */
object Texts {

	private val HERBS = setOf("guam", "ranarr")

	/** A running total, which extension functions add to. */
	class Tally(var total: Int) {

		/** Adds the amount to the total. */
		fun add(amount: Int) {
			total += amount
		}
	}

	/** The text in capitals, as if shouted. */
	fun String.shout(): String = this.uppercase() + "!"

	/** The text shouted twice. */
	fun String.shoutTwice(): String = shout() + " " + this.shout()

	/** Whether the text is a herb's name. */
	private fun String.isHerb(): Boolean = this in HERBS

	/** The herbs among the names. */
	fun herbs(names: List<String>): List<String> = names.filter { it.isHerb() }

	/** Adds the amount to the tally twice. */
	fun Tally.addTwice(amount: Int) {
		add(amount)
		this.add(amount)
	}

	/** Twice the tally's total. */
	fun Tally.doubled(): Int = total * 2

	/** The text shouted, or null for no text. */
	fun loud(text: String?): String? = text?.shout()
}
