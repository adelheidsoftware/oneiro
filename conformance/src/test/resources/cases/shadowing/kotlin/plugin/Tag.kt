package plugin

/** Function references to a private function below them, whose parameter has the function's name. */
class Tag(private val prefix: String) {

	fun all(names: List<String>): List<String> {
		return names.map(::label)
	}

	fun labeller(): (String) -> String {
		return ::label
	}

	private fun label(label: String): String {
		return prefix + label
	}
}
