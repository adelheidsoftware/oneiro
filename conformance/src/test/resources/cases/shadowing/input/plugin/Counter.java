package plugin;

import static org.adelheid.oneiro.rs3.Globals.log;

import org.adelheid.oneiro.api.Plugin;

/** Remembers the last count it showed, through a method whose parameter has the field's name. */
public final class Counter extends Plugin {

	private int shown;

	@Override
	protected void startUp() {
		show(3);
		log("shown", shown);
	}

	private void show(int shown) {
		this.shown = shown;
	}
}
