package plugin

/** Dev code that uses the main source set's types as the main set's Lua makes them. */
object Checks {

	/** How many summary styles there are. */
	fun styles(): Int = SummaryStyle.entries.size

	/** A style's name. */
	fun styleName(style: SummaryStyle): String = style.name

	/** Where the skills section comes. */
	fun skillsOrder(): Int = Section.SKILLS.ordinal

	/** The skills section's name and title. */
	fun skillsLabel(): String = Section.SKILLS.name + ": " + Section.SKILLS.title

	/** Whether the speed is the fast one, by its order. */
	fun isFast(speed: Speed): Boolean = speed.ordinal == 1

	/** A summary of one level. */
	fun summary(xp: Int): Summary = Summary(xp, 1)

	/** A tally of three, doubled. */
	fun doubledThree(): Int = Tally(3).doubled()

	/** Whether the value is a shape, a circle among them. */
	fun isShape(value: Any): Boolean = value is Shape

	/** A tab of the bank. */
	fun tab(bank: Bank, name: String): Bank.Tab = bank.Tab(name)
}
