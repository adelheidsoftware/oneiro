package plugin.core;

import java.util.List;
import java.util.Map;
import org.adelheid.oneiro.api.LuaModule;
import org.adelheid.oneiro.api.LuaReturnValues;

/**
 * The seeds, the hidden rankings a blueprint can have, and the operations on sets of them, from Blueprint-Helper's
 * hand-written seeds.lua. A set of seeds is a list of seed ids. The tables indexed by level count from level 0
 * (Perfect), which a Java list cannot describe, so they are maps from level to value.
 */
@LuaModule("src/core/seeds")
public final class Seeds {

	/** What {@link #score} returns: seeds and distinct answers per level, and the farthest answer. */
	@LuaReturnValues
	public record Score(Map<Integer, Integer> counts, Map<Integer, Integer> answers, int farthest) {
	}

	/** What {@link #split} returns: the survivors each level would keep, and how many. */
	@LuaReturnValues
	public record Split(Map<Integer, List<Integer>> buckets, Map<Integer, Integer> counts) {
	}

	private Seeds() {
	}

	/** @return count: how many different Perfect arrangements the survivors still allow */
	public static native int distinctAnswers(List<Integer> survivors);

	/** @return swaps: the most swaps any survivor's Perfect arrangement is away from the arrangement */
	public static native int farthest(int arrangement, List<Integer> survivors);

	/**
	 * Scores the arrangement over the survivors in one pass: how many seeds and how many distinct answers each level
	 * would keep, and the farthest answer, in swaps from the arrangement, among the seeds that would not be Perfect.
	 */
	public static native Score score(List<Integer> survivors, int arrangement);

	/** Splits the survivors by the level each seed gives the arrangement. */
	public static native Split split(List<Integer> survivors, int arrangement);
}
