package plugin

/** Things that can say what they are, with a shared way of saying it. */
interface Describable {

	fun label(): String

	/** The thing's label and what it is. */
	fun describe(): String {
		return "$OPENING ${label()} ${kind()}"
	}

	/** What kind of thing it is; a thing unless it says otherwise. */
	fun kind(): String {
		return "thing"
	}

	companion object {

		/** How a description starts. */
		const val OPENING = "a"

		/** The thing's description in capitals. */
		fun shout(thing: Describable): String {
			return thing.describe().uppercase()
		}
	}
}
