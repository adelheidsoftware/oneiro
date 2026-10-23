package plugin.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Arrangements of the five Discovery modules as permutations. An arrangement maps slot to module, both 1..5, so
 * 2 1 3 4 5 means module 2 sits in slot 1 and module 1 in slot 2. Module n is whatever sat in slot n of the first
 * graded row, so that row is always 1 2 3 4 5, the first entry of `list`. The rest of the engine refers to an
 * arrangement by its index in `list`, counting from 1, and the three tables built here let the hot loops in seeds.lua
 * and search.lua work on integers only:
 * - COMPOSE is a ∘ b, applying b and then a. Swapping slots i and j of arrangement c is c ∘ t, where t is the
 *   arrangement that only exchanges slots i and j; scoring.lua and seeds.lua also use it to put a seed's ranks into an
 *   arrangement's slots.
 * - INV is the inverse of an arrangement, for DIST below and for the Perfect arrangements in scoring.lua.
 * - DIST is the smallest number of swaps that turns one arrangement into another: 1 2 3 4 5 to 2 1 3 4 5 is one swap,
 *   to 2 3 1 4 5 two, and nothing is more than four away.
 */
public final class Permutations {

	/** Two slots, i before j, and the index of the arrangement that only exchanges them. */
	public record Transposition(int i, int j, int index) {
	}

	public static final int N = 5;

	/** Every arrangement in lexicographic order, so the first is the identity 1 2 3 4 5. */
	public static final List<int[]> list = new ArrayList<>();
	private static final Map<Integer, Integer> indexByKey = new HashMap<>();

	private Permutations() {
	}

	private static int keyOf(int[] p) {
		return p[0] * 10000 + p[1] * 1000 + p[2] * 100 + p[3] * 10 + p[4];
	}

	/** Adds every arrangement that starts with the prefix, in lexicographic order. */
	private static void generate(List<Integer> prefix, List<Integer> remaining) {
		if (remaining.isEmpty()) {
			int[] p = {prefix.get(0), prefix.get(1), prefix.get(2), prefix.get(3), prefix.get(4)};
			list.add(p);
			indexByKey.put(keyOf(p), list.size());
			return;
		}
		for (int i = 0; i < remaining.size(); i++) {
			prefix.add(remaining.get(i));
			List<Integer> rest = new ArrayList<>();
			for (int j = 0; j < remaining.size(); j++) {
				if (j != i) {
					rest.add(remaining.get(j));
				}
			}
			generate(prefix, rest);
			prefix.remove(prefix.size() - 1);
		}
	}

	static {
		generate(new ArrayList<>(), List.of(1, 2, 3, 4, 5));
	}

	public static final int COUNT = list.size(); // 120

	/**
	 * @param arrangement slot -> module
	 * @return index: into Permutations.list, nil unless the array is a permutation of 1..5
	 */
	public static @Nullable Integer indexOf(int[] arrangement) {
		if (arrangement.length != N) {
			return null;
		}
		for (int s = 0; s < N; s++) {
			int module = arrangement[s];
			if (module < 1 || module > N) {
				return null;
			}
		}
		return indexByKey.get(keyOf(arrangement));
	}

	/** The index of a ∘ b, where (a ∘ b)[s] = a[b[s]], for every pair: row a, column b. */
	public static final int[] COMPOSE = new int[COUNT * COUNT];
	/** The index of the inverse of each arrangement. */
	public static final int[] INV = new int[COUNT];

	static {
		for (int a = 1; a <= COUNT; a++) {
			int[] pa = list.get(a - 1);
			int[] inverse = new int[N];
			for (int s = 0; s < N; s++) {
				inverse[pa[s] - 1] = s + 1;
			}
			INV[a - 1] = indexByKey.get(keyOf(inverse));
			int base = (a - 1) * COUNT;
			for (int b = 1; b <= COUNT; b++) {
				int[] pb = list.get(b - 1);
				int[] composed = {pa[pb[0] - 1], pa[pb[1] - 1], pa[pb[2] - 1], pa[pb[3] - 1], pa[pb[4] - 1]};
				COMPOSE[base + b - 1] = indexByKey.get(keyOf(composed));
			}
		}
	}

	/** Swapping slots i and j of arrangement c gives c ∘ t, with t the transposition of i and j. */
	public static final List<Transposition> TRANSPOSITIONS = new ArrayList<>();
	private static final Map<Integer, Map<Integer, Integer>> transpositionAt = new HashMap<>();

	static {
		for (int i = 1; i < N; i++) {
			transpositionAt.put(i, new HashMap<>());
			for (int j = i + 1; j <= N; j++) {
				int[] t = {1, 2, 3, 4, 5};
				t[i - 1] = j;
				t[j - 1] = i;
				TRANSPOSITIONS.add(new Transposition(i, j, indexByKey.get(keyOf(t))));
				transpositionAt.get(i).put(j, TRANSPOSITIONS.size());
			}
		}
	}

	/**
	 * @param arrangement arrangement index
	 * @param i slot
	 * @param j slot, in either order relative to i
	 * @return arrangement: index after swapping the two slots
	 */
	public static int swap(int arrangement, int i, int j) {
		if (i > j) {
			int first = j;
			j = i;
			i = first;
		}
		int transposition = TRANSPOSITIONS.get(transpositionAt.get(i).get(j) - 1).index();
		return COMPOSE[(arrangement - 1) * COUNT + transposition - 1];
	}

	private static int cycleCount(int[] p) {
		boolean[] seen = new boolean[N];
		int count = 0;
		for (int s = 0; s < N; s++) {
			if (!seen[s]) {
				count++;
				// Follow the cycle from slot s; the modules in p count from 1
				int k = s + 1;
				while (!seen[k - 1]) {
					seen[k - 1] = true;
					k = p[k - 1];
				}
			}
		}
		return count;
	}

	/**
	 * The number of swaps that turn arrangement a into b, for every pair: row a, column b. The rearrangement a⁻¹ ∘ b
	 * splits into cycles (a module already in place is a cycle of one), and a cycle of length k takes k - 1 swaps, so
	 * the total is N minus the number of cycles.
	 */
	public static final int[] DIST = new int[COUNT * COUNT];

	static {
		for (int a = 1; a <= COUNT; a++) {
			int base = (a - 1) * COUNT;
			int invBase = (INV[a - 1] - 1) * COUNT;
			for (int b = 1; b <= COUNT; b++) {
				DIST[base + b - 1] = N - cycleCount(list.get(COMPOSE[invBase + b - 1] - 1));
			}
		}
	}
}
