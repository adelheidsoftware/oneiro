package plugin

/** Lists, maps and sets as plain Lua tables. */
object Tables {

	fun numbers(): List<Int> {
		val numbers = ArrayList<Int>()
		numbers.add(3)
		numbers.add(1)
		numbers.add(0, 2)
		numbers[1] = 5
		numbers.sortWith { a, b -> a.compareTo(b) }
		return numbers
	}

	fun inspect(numbers: List<Int>): Boolean {
		return 5 in numbers && numbers.indexOf(2) == 0 && numbers.isNotEmpty() && numbers[0] == 2
	}

	fun removeFirst(numbers: MutableList<Int>): Int {
		return numbers.removeAt(0)
	}

	fun scores(): Map<String, Int> {
		val scores = HashMap<String, Int>()
		scores["ann"] = 3
		scores["bob"] = 5
		scores.remove("bob")
		return scores
	}

	fun lookup(scores: Map<String, Int>, name: String): Int {
		if (name !in scores) {
			return (scores[name] ?: -1) + scores.size
		}
		return scores.getValue(name)
	}

	fun seen(values: List<Int>): Set<Int> {
		val seen = HashSet<Int>()
		for (value in values) {
			seen.add(value)
		}
		seen.remove(0)
		return seen
	}

	fun has(seen: Set<Int>, value: Int): Boolean {
		return value in seen && seen.isNotEmpty() && seen.size > 0
	}

	fun doubled(source: List<String>): List<String> {
		val copy = source.toMutableList()
		copy.addAll(source)
		return copy
	}

	fun fixed(): List<String> {
		return listOf("a", "b")
	}

	fun fixedMap(): Map<String, Int> {
		return mapOf("a" to 1, "b" to 2)
	}

	fun fixedSet(): Set<String> {
		return setOf("x", "y")
	}

	fun sorted(values: List<Int>): List<Int> {
		val copy = values.toMutableList()
		copy.sort()
		return copy
	}

	fun weighed(scores: Map<String, Int>): Int {
		var total = 0
		for (name in scores.keys) {
			total += name.length
		}
		for (score in scores.values) {
			total += score
		}
		return total
	}
}
