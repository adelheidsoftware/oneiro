package plugin

/** A count other files would read, beside an object. */
val strayCount = 1

/** A helper other files would call, beside an object. */
fun shared(): Int = strayCount

/** An object beside a public top-level function and property. */
object Stray {

	/** One. */
	fun one(): Int = shared()
}
