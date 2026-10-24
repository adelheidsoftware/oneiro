package plugin

/** Constructs Lua cannot express, each rejected with its reason. */
object Rejected {

	fun sum(a: Int, b: Int): Int {
		return a + b
	}

	fun sum(a: Int, b: Int, c: Int): Int {
		return a + b + c
	}

	fun spin() {
		synchronized(this) {
		}
	}

	fun check(ready: Boolean) {
		assert(ready) { "not ready" }
	}

	fun bump(value: Int): Int {
		var current = value
		val next = current++
		return next + current
	}

	fun size(count: Int): Int {
		val names = arrayOfNulls<String>(count)
		return count
	}

	fun search() {
		outer@ for (i in 0 until 3) {
			for (j in 0 until 3) {
				break@outer
			}
		}
	}
}
