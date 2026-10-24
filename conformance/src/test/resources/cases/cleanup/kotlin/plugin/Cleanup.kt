package plugin

/** Work that always tidies up after itself. */
object Cleanup {

	/** A reader of a text, which counts when it is closed. */
	class Reader(private val text: String) : AutoCloseable {

		/** The text's first letter; an empty text has none. */
		fun first(): String {
			if (text.isEmpty()) {
				throw IllegalStateException("nothing to read")
			}
			return text.substring(0, 1)
		}

		override fun close() {
			closes++
		}

		companion object {

			/** How many readers have been closed. */
			var closes = 0
		}
	}

	/** How many tasks have run, failed or not. */
	var runs = 0

	/** Runs the task, and counts the run even when the task fails. */
	fun counted(task: () -> Unit) {
		try {
			task()
		} finally {
			// A failed run counts too
			runs++
			// The task's error, if any, goes on to the caller
		}
	}

	/** The text's first letter, or the fallback, counting a run either way. */
	fun firstOr(text: String, fallback: String): String {
		try {
			return Reader(text).first()
		} catch (e: RuntimeException) {
			return fallback
		} finally {
			runs++
		}
	}

	/** The text's first letter, read with a reader that is closed whatever happens. */
	fun read(text: String): String {
		return Reader(text).use { reader -> reader.first() }
	}
}
