package plugin

import org.adelheid.oneiro.rs3.Globals.log

/** Errors caught with pcall. */
object Parsing {

	private var failures = 0

	private fun measured(text: String): Int {
		if (text.isEmpty()) {
			throw IllegalArgumentException("empty text")
		}
		if (text.startsWith("!")) {
			throw IllegalArgumentException("not a measure: $text")
		}
		return text.length
	}

	/**
	 * Runs the task, and logs and counts a failure instead of passing it on.
	 *
	 * @param task what to run
	 */
	fun run(task: () -> Unit) {
		try {
			task()
		} catch (e: RuntimeException) {
			failures++
			log("The task failed:", e.message)
		}
	}

	fun number(text: String, fallback: Int): Int {
		return try {
			measured(text)
		} catch (e: RuntimeException) {
			fallback
		}
	}

	fun outcome(text: String): String {
		try {
			val value = measured(text)
			return "number $value"
		} catch (e: Exception) {
			// The message says what went wrong
			return "error ${e.message}"
		}
	}

	fun strict(text: String) {
		try {
			measured(text)
		} catch (e: RuntimeException) {
			if (e.message!!.contains("empty")) {
				return
			}
			throw e
		}
	}

	fun failureCount(): Int {
		return failures
	}

	fun check(problem: String) {
		val failure = IllegalStateException("broken: $problem")
		if (problem.isNotEmpty()) {
			throw failure
		}
	}

	fun message(problem: String): String? {
		val failure = RuntimeException(problem)
		return failure.message
	}
}
