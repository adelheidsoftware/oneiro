package plugin

/** Counting, grouping and queueing with the collections' own functions. */
object Tally {

	/** How often each word comes up. */
	fun counts(words: List<String>): Map<String, Int> {
		val counts = HashMap<String, Int>()
		words.forEach { word -> counts[word] = (counts[word] ?: 0) + 1 }
		return counts
	}

	/** The words by their first letter. */
	fun byInitial(words: List<String>): Map<String, List<String>> {
		val groups = HashMap<String, MutableList<String>>()
		for (word in words) {
			groups.getOrPut(word.substring(0, 1)) { ArrayList() }.add(word)
		}
		return groups
	}

	/** Drops the short words, and the empty ones from the set. */
	fun prune(words: MutableList<String>, seen: MutableSet<String>) {
		words.removeAll { word -> word.length < 3 }
		seen.removeAll { word -> word.isEmpty() }
	}

	/** Whether a feature is on: one nobody set is on. */
	fun enabled(features: Map<String, Boolean>, name: String): Boolean {
		return features[name] ?: true
	}

	/** The fields of a line of comma-separated values. */
	fun fields(line: String): List<String> {
		return line.split(",")
	}

	/** Puts a job at each end of the queue, and takes the one at the front. */
	fun next(jobs: ArrayDeque<String>): String {
		jobs.addLast("later")
		jobs.addFirst("urgent")
		return jobs.removeFirst()
	}

	/** The counts as Kotlin writes a map. */
	fun describe(counts: Map<String, Int>): String {
		return "counts: $counts"
	}

	/** Notes the tick a name was first seen at. */
	fun remember(firstSeen: MutableMap<String, Int>, name: String, tick: Int) {
		firstSeen.getOrPut(name) { tick }
	}

	/** The word reversed and bracketed. */
	fun reversed(word: String): String {
		return "[${word.reversed()}]"
	}
}
