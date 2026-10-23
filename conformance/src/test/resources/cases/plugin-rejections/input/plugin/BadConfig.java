package plugin;

import java.util.List;
import org.adelheid.oneiro.api.ConfigGroup;
import org.adelheid.oneiro.api.ConfigItem;

/** Settings that break the rules. */
@ConfigGroup("bad")
public interface BadConfig {

	@ConfigItem(keyName = "computed", name = "Computed")
	default int computed() {
		int value = 1;
		return value;
	}

	@ConfigItem(keyName = "missing", name = "Missing")
	int missing();

	@ConfigItem(keyName = "names", name = "Names")
	default List<String> names() {
		return List.of();
	}

	default int unmarked() {
		return 1;
	}
}
