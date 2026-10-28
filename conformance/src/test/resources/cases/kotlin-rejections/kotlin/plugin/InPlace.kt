package plugin

import org.adelheid.oneiro.rs3.Globals.log

/** Values that would need a local before their statement where nothing may run early, and code Lua runs in place. */
object InPlace {

	/** The first of the names, or null. */
	fun nearest(names: List<String>): String? = names.firstOrNull()

	/** A chain in the test of takeIf, a lambda Lua reads in place. */
	fun noneEmpty(names: List<String>): List<String>? = names.takeIf { it.count { name -> name.isEmpty() } == 0 }

	/** A chain on the right of ?:, which runs only when the value is null. */
	fun orEmpty(name: String?, names: List<String>): Int = name?.length ?: names.count { it.isEmpty() }

	/** A call's value read twice in a branch of an if. */
	fun nearestLength(names: List<String>, look: Boolean): Int = if (look) nearest(names)?.length ?: 0 else 0

	/** A call's value read twice after another call in the same arguments. */
	fun both(names: List<String>) {
		log(nearest(names) ?: "", nearest(names)?.length ?: 0)
	}

	/** A call's value read twice in a loop's condition, which runs each time round. */
	fun drain(names: MutableList<String>) {
		while ((nearest(names)?.length ?: 0) > 0) {
			names.removeAt(0)
		}
	}

	/** A chain in the argument of a safe call, which runs only when the receiver is not null. */
	fun cut(name: String?, names: List<String>): String? = name?.substring(names.count { it.isEmpty() })

	/** A call's value read twice in a parameter's default. */
	fun padded(text: String, width: Int = nearest(listOf(text))?.length ?: 0): String = "$text$width"

	/** A return from a chain's lambda, whose code runs in place in the loop. */
	fun doubled(levels: List<Int>): List<Int> = levels.map {
		if (it < 0) return@map 0
		it * 2
	}
}
