package plugin.core;

import static plugin.core.Permutations.COMPOSE;
import static plugin.core.Permutations.COUNT;
import static plugin.core.Permutations.TRANSPOSITIONS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.adelheid.oneiro.api.LuaReturnValues;
import org.jspecify.annotations.Nullable;
import plugin.core.Permutations.Transposition;

/**
 * The endgame search: given the current row and the surviving seeds, the swap that minimises the number of further
 * swaps needed in the worst case.
 *
 * It asks, for depth 1, 2, 3 and so on, whether every survivor can be brought to Perfect within that many swaps; the
 * first depth that works is the bound reported with the move. At each position it tries the ten swaps, splits the
 * survivors by the level each swap would produce, and requires every outcome to be solvable one swap sooner. It runs
 * once 12 or fewer Perfect arrangements remain (see deduce.lua); with the opening table ahead of it, it reaches
 * Perfect within 11 swaps on every blueprint, which is the best any strategy can guarantee. A node budget bounds the
 * cost, and when it runs out the caller falls back to the greedy layer.
 */
public final class Search {

	/** A suggested swap: the two slots, and the most swaps still needed, this one included. */
	public record Move(int i, int j, int bound) {
	}

	/**
	 * A swap worth trying from some arrangement: the swap, the arrangement it leads to, the distinct answers and the
	 * seeds of its worst outcome, its farthest answer in swaps, and how many survivors each level would keep.
	 */
	private record Candidate(Transposition swap, int swapped, int worstAnswers, int worstSeeds, int farthest,
			Map<Integer, Integer> counts) {
	}

	/** The swaps worth trying, best first; or, when one swap makes every survivor Perfect, that swap alone. */
	@LuaReturnValues
	private record Candidates(List<Candidate> cands, @Nullable Candidate immediate) {
	}

	/**
	 * For each number of graded swaps d, the most distinct answers any strategy can tell apart in d swaps. A swap is
	 * answered by one of five levels other than Perfect, each leading to a position with d - 1 swaps left, and by
	 * Perfect, which finishes exactly one answer. So the value for d is 1 + 5 times the value for d - 1: 1, 6, 31,
	 * 156, ... A position with more answers than that cannot be finished in d swaps, whatever is tried.
	 */
	private static final Map<Integer, Integer> RESOLVABLE = new HashMap<>();

	static {
		RESOLVABLE.put(0, 0);
		for (int d = 1; d <= 20; d++) {
			RESOLVABLE.put(d, 1 + 5 * RESOLVABLE.get(d - 1));
		}
	}

	/** The message of the error raised when the node budget runs out, which find catches. */
	private static final String NODE_BUDGET_SPENT = "search: node budget spent";

	private static int nodes;
	private static int nodeCap;

	private Search() {
	}

	/**
	 * The swaps from the arrangement worth trying at this depth, best first, or the one that makes every survivor
	 * Perfect. A swap is dropped when either bound says it cannot finish in depth - 1 more swaps.
	 */
	private static Candidates candidates(int arrangement, List<Integer> survivors, int depth) {
		List<Candidate> cands = new ArrayList<>();
		for (Transposition swap : TRANSPOSITIONS) {
			int swapped = COMPOSE[(arrangement - 1) * COUNT + swap.index() - 1];
			var score = Seeds.score(survivors, swapped);
			int worstAnswers = 0;
			int worstSeeds = 0;
			boolean done = true;
			for (int level = 1; level <= 5; level++) {
				if (score.counts().get(level) > 0) {
					done = false;
					worstAnswers = Math.max(worstAnswers, score.answers().get(level));
					worstSeeds = Math.max(worstSeeds, score.counts().get(level));
				}
			}
			if (done) {
				return new Candidates(new ArrayList<>(), new Candidate(swap, swapped, 0, 0, 0, score.counts()));
			}
			if (worstAnswers <= RESOLVABLE.get(depth - 1) && score.farthest() <= depth - 1) {
				cands.add(new Candidate(swap, swapped, worstAnswers, worstSeeds, score.farthest(), score.counts()));
			}
		}
		cands.sort((x, y) -> compare(x, y));
		return new Candidates(cands, null);
	}

	/**
	 * The order candidates are tried in: fewest answers in the worst outcome first, then the nearest farthest answer,
	 * then the fewest seeds in the worst outcome, and the earlier slots last, so that the order is the same on every
	 * run.
	 */
	private static int compare(Candidate x, Candidate y) {
		if (x.worstAnswers() != y.worstAnswers()) {
			return x.worstAnswers() - y.worstAnswers();
		}
		if (x.farthest() != y.farthest()) {
			return x.farthest() - y.farthest();
		}
		if (x.worstSeeds() != y.worstSeeds()) {
			return x.worstSeeds() - y.worstSeeds();
		}
		if (x.swap().i() != y.swap().i()) {
			return x.swap().i() - y.swap().i();
		}
		return x.swap().j() - y.swap().j();
	}

	/** Whether every level the candidate can produce leaves survivors that are solvable in depth - 1 swaps. */
	private static boolean everyOutcomeSolvable(Candidate cand, List<Integer> survivors, int depth) {
		var split = Seeds.split(survivors, cand.swapped());
		for (int level = 1; level <= 5; level++) {
			if (cand.counts().get(level) > 0 && !solvable(cand.swapped(), split.buckets().get(level), depth - 1)) {
				return false;
			}
		}
		return true;
	}

	/** Can every survivor be brought to Perfect within depth swaps from the arrangement? */
	private static boolean solvable(int arrangement, List<Integer> survivors, int depth) {
		if (survivors.isEmpty()) {
			return true;
		}
		if (depth == 0) {
			return false;
		}
		if (Seeds.distinctAnswers(survivors) > RESOLVABLE.get(depth)) {
			return false;
		}
		if (Seeds.farthest(arrangement, survivors) > depth) {
			return false;
		}
		nodes++;
		if (nodes > nodeCap) {
			throw new IllegalStateException(NODE_BUDGET_SPENT);
		}
		var found = candidates(arrangement, survivors, depth);
		if (found.immediate() != null) {
			return true;
		}
		if (depth == 1) {
			return false;
		}
		for (Candidate cand : found.cands()) {
			if (everyOutcomeSolvable(cand, survivors, depth)) {
				return true;
			}
		}
		return false;
	}

	/** The first candidate, in preference order, whose every outcome is solvable in depth - 1 swaps. */
	private static Candidate choose(int arrangement, List<Integer> survivors, int depth) {
		var found = candidates(arrangement, survivors, depth);
		if (found.immediate() != null) {
			return found.immediate();
		}
		for (Candidate cand : found.cands()) {
			if (everyOutcomeSolvable(cand, survivors, depth)) {
				return cand;
			}
		}
		throw new IllegalStateException("search: no feasible move at a depth that was just proven solvable");
	}

	/**
	 * @param current arrangement index of the row as it stands
	 * @param survivors surviving seeds, none of which has current as its answer
	 * @param cap most nodes to expand before giving up
	 * @return move: the slots to swap and the most swaps still needed; nil when the cap was hit
	 */
	public static @Nullable Move find(int current, List<Integer> survivors, int cap) {
		nodes = 0;
		nodeCap = cap;
		try {
			int depth = Math.max(Seeds.farthest(current, survivors), 1);
			while (!solvable(current, survivors, depth)) {
				depth++;
			}
			Transposition swap = choose(current, survivors, depth).swap();
			return new Move(swap.i(), swap.j(), depth);
		} catch (RuntimeException e) {
			// The message of a Lua error starts with the place it was raised
			if (e.getMessage().contains(NODE_BUDGET_SPENT)) {
				return null;
			}
			throw e;
		}
	}
}
