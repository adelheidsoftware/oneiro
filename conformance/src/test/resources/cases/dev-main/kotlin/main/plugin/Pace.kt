package plugin

/** Reads the order of speeds, which makes their constants objects. */
object Pace {

	/** The speed's place in its order. */
	fun order(speed: Speed): Int = speed.ordinal
}
