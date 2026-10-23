package plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Plants each herb it is given, keeping them in order. */
public class Planter implements Consumer<String> {

	private final List<String> planted = new ArrayList<>();

	@Override
	public void accept(String herb) {
		planted.add(herb);
	}

	public List<String> all() {
		return planted;
	}
}
