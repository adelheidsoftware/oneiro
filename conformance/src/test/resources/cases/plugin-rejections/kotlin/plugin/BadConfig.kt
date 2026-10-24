package plugin

import org.adelheid.oneiro.api.ConfigGroup
import org.adelheid.oneiro.api.ConfigItem

/** Settings that break the rules. */
@ConfigGroup("bad")
interface BadConfig {

	@ConfigItem(keyName = "computed", name = "Computed")
	fun computed(): Int {
		val value = 1
		return value
	}

	@ConfigItem(keyName = "missing", name = "Missing")
	fun missing(): Int

	@ConfigItem(keyName = "names", name = "Names")
	fun names(): List<String> = listOf()

	fun unmarked(): Int = 1
}
