package plugin.core;

import java.util.List;
import org.adelheid.oneiro.api.LuaName;
import org.jspecify.annotations.Nullable;

/** Interop with existing Lua: names it calls, tables it passes, and a hand-written module it provides. */
public class Tracker {

	/** One board position, as the hand-written reader reports it. */
	public record Position(@Nullable Integer spriteId, boolean visible) {
	}

	/** What the hand-written view reads. */
	public record Summary(int seen, int level) {
	}

	public int seen;

	@LuaName("Ingest")
	public void ingest(List<Position> positions) {
		for (Position position : positions) {
			Integer sprite = position.spriteId();
			if (sprite != null && position.visible()) {
				seen++;
			}
		}
	}

	@LuaName("Summary")
	public Summary summary() {
		return new Summary(seen, Levels.fromPenalty(seen) + Levels.PERFECT);
	}
}
