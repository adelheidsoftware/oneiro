package plugin;

import java.util.List;
import java.util.function.Function;

/** Method references to a private method below them, whose parameter has the method's name. */
public final class Tag {

	private final String prefix;

	public Tag(String prefix) {
		this.prefix = prefix;
	}

	public List<String> all(List<String> names) {
		return names.stream().map(this::label).toList();
	}

	public Function<String, String> labeller() {
		return this::label;
	}

	private String label(String label) {
		return prefix + label;
	}
}
