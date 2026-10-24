package plugin

/** Counters whose level can drop below zero. */
class Boosted : Counters() {

	override fun level(): Int {
		return super.level() - 5
	}
}
