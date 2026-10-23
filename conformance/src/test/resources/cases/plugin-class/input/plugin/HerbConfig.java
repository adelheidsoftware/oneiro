package plugin;

import org.adelheid.oneiro.api.ConfigGroup;
import org.adelheid.oneiro.api.ConfigItem;

/** The herb tracker's settings. */
@ConfigGroup("herbs")
public interface HerbConfig {

	@ConfigItem(keyName = "greet", name = "Greeting", description = "Logs a line when the plugin starts")
	default boolean greet() {
		return true;
	}

	@ConfigItem(keyName = "hideHans", name = "Hide Hans")
	default boolean hideHans() {
		return false;
	}

	@ConfigItem(keyName = "runs", name = "Runs", description = "How many times the plugin has stopped")
	default int runs() {
		return 0;
	}

	@ConfigItem(keyName = "runs", name = "Runs", description = "How many times the plugin has stopped")
	void setRuns(int runs);

	/** What the tracker reports each time. */
	@ConfigItem(keyName = "mode", name = "Mode")
	default Mode mode() {
		return Mode.COUNT;
	}

	@ConfigItem(keyName = "mode", name = "Mode")
	void setMode(Mode mode);
}
