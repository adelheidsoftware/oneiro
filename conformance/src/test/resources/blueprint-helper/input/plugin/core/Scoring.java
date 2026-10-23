package plugin.core;

import static plugin.core.Permutations.COMPOSE;
import static plugin.core.Permutations.COUNT;
import static plugin.core.Permutations.INV;
import static plugin.core.Permutations.N;
import static plugin.core.Permutations.list;

/**
 * How the game grades an arrangement, and the table that makes grading a single lookup.
 *
 * A blueprint hides a rank 1..5 for each module (M) and for each slot (S). The penalty of an arrangement P is the
 * sum over slots s of |M[P[s]] - S[s]|, and its level is what the game shows. For example, with module ranks
 * M = 2 5 3 4 1, slot ranks S = 4 1 2 5 3 and the arrangement P = 1 2 3 4 5 (module s in slot s), the ranks sitting
 * in the slots are 2 5 3 4 1 and the penalty is |2-4| + |5-1| + |3-2| + |4-5| + |1-3| = 10, which is Poor.
 *
 * M and S are permutations, so the ranks sitting in the slots are sigma = M ∘ P, one COMPOSE lookup, and the level is
 * GRADE[sigma][S], one more. GRADE holds all 120 x 120 pairs and is built once here. This rule reproduces every
 * graded row in a corpus of 25 recorded puzzles (535 arrangements).
 */
public final class Scoring {

	/** The level of the penalty between the permutations sigma and s, for every pair: row sigma, column s. */
	public static final int[] GRADE = new int[COUNT * COUNT];

	static {
		for (int sigma = 1; sigma <= COUNT; sigma++) {
			int[] ranksInSlots = list.get(sigma - 1);
			int base = (sigma - 1) * COUNT;
			for (int s = 1; s <= COUNT; s++) {
				int[] slotRanks = list.get(s - 1);
				int penalty = 0;
				for (int k = 0; k < N; k++) {
					penalty += Math.abs(ranksInSlots[k] - slotRanks[k]);
				}
				GRADE[base + s - 1] = Levels.fromPenalty(penalty);
			}
		}
	}

	private Scoring() {
	}

	/**
	 * The arrangement that scores Perfect for these ranks: M[P[s]] = S[s], so P is the inverse of M composed with S.
	 *
	 * @param moduleRanks permutation index of the module ranks
	 * @param slotRanks permutation index of the slot ranks
	 * @return arrangement: permutation index
	 */
	public static int perfectArrangement(int moduleRanks, int slotRanks) {
		return COMPOSE[(INV[moduleRanks - 1] - 1) * COUNT + slotRanks - 1];
	}
}
