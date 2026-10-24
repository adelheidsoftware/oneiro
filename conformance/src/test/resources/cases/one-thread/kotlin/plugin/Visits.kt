package plugin

/** Counts visits; written for threads, which the client's one thread makes no difference to. */
object Visits {

	@Volatile
	private var count = 0

	@Synchronized
	fun visit(): Int {
		count++
		return count
	}
}
