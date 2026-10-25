package plugin

/** Lambdas, function types, fun interfaces, function references and generics. */
object Functions {

	/** A check the plugin defines itself. */
	fun interface Check {
		fun test(value: Int): Boolean
	}

	/** Where values go, one at a time. */
	fun interface Sink {
		fun take(value: Int)
	}

	fun <T> keep(items: List<T>, keep: (T) -> Boolean): List<T> {
		val kept = ArrayList<T>()
		for (item in items) {
			if (keep(item)) {
				kept.add(item)
			}
		}
		return kept
	}

	fun matching(values: List<Int>, check: Check): Int {
		var matching = 0
		for (value in values) {
			if (check.test(value)) {
				matching++
			}
		}
		return matching
	}

	fun isEven(value: Int): Boolean {
		return value % 2 == 0
	}

	fun evens(values: List<Int>): List<Int> {
		return keep(values) { value -> value % 2 == 0 }
	}

	fun evenCount(values: List<Int>): Int {
		return matching(values, ::isEven)
	}

	fun applied(start: Int): Int {
		val twice = { x: Int -> x * 2 }
		val sum = { a: Int, b: Int ->
			val total = a + b
			total
		}
		val name = { "functions" }
		val nothing = {}
		nothing()
		return sum(twice(start), name().length)
	}

	fun each(values: List<Int>, sink: Sink) {
		for (value in values) {
			sink.take(value)
		}
	}

	fun collected(values: List<Int>): List<Int> {
		val found = ArrayList<Int>()
		each(values, found::add)
		return found
	}
}
