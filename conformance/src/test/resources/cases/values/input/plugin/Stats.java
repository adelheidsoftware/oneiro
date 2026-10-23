package plugin;

import java.util.List;
import org.adelheid.oneiro.api.LuaModule;
import org.adelheid.oneiro.api.LuaReturnValues;

/** The hand-written statistics module. */
@LuaModule("src/stats")
public final class Stats {

	/** What extremes returns, declared with the function. */
	@LuaReturnValues
	public record Extremes(int least, int most) {
	}

	private Stats() {
	}

	public static native Summary summarise(List<Integer> values);

	public static native Extremes extremes(List<Integer> values);
}
