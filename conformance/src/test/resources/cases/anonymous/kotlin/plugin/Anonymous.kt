package plugin

/** Object expressions: functions where Kotlin means one, objects otherwise. */
object Anonymous {

	/** Something that hears words and has a name. */
	interface Listener {

		fun heard(word: String): String

		fun name(): String
	}

	/** A shape whose area each kind gives. */
	abstract class Shape(val name: String) {

		abstract fun area(): Double

		fun describe(): String {
			return "$name of ${area()}"
		}
	}

	/** Sorts the words longest first, with an object expression as the comparator. */
	fun longestFirst(words: List<String>): List<String> {
		val sorted = words.toMutableList()
		sorted.sortWith(object : Comparator<String> {
			override fun compare(a: String, b: String): Int {
				return b.length - a.length
			}
		})
		return sorted
	}

	/** Runs a task the given number of times, which counts in the list. */
	fun repeated(times: Int, counts: MutableList<Int>) {
		val task = object : Runnable {
			override fun run() {
				counts.add(counts.size)
			}
		}
		for (i in 0 until times) {
			task.run()
		}
	}

	/** A listener that answers with the word it heard, and how many it has heard. */
	fun echo(prefix: String): Listener {
		return object : Listener {
			private var heardCount = 0

			override fun heard(word: String): String {
				heardCount++
				return "$prefix$word ($heardCount)"
			}

			override fun name(): String {
				return "echo"
			}
		}
	}

	/** The words, each with its length after the separator, through a class of this function's own. */
	fun counted(words: List<String>, separator: String): List<String> {
		class Count(private val word: String) {

			fun text(): String {
				return "$word$separator${word.length}"
			}
		}
		val texts = ArrayList<String>()
		for (word in words) {
			texts.add(Count(word).text())
		}
		return texts
	}

	/** A square of side 1, made on the spot. */
	fun unitSquare(): Shape {
		return object : Shape("square") {
			override fun area(): Double {
				return 1.0
			}
		}
	}
}
