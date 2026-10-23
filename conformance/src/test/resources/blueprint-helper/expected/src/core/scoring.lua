-- How the game grades an arrangement, and the table that makes grading a single lookup.
--
-- A blueprint hides a rank 1..5 for each module (M) and for each slot (S). The penalty of an arrangement P is the
-- sum over slots s of |M[P[s]] - S[s]|, and its level is what the game shows. For example, with module ranks
-- M = 2 5 3 4 1, slot ranks S = 4 1 2 5 3 and the arrangement P = 1 2 3 4 5 (module s in slot s), the ranks sitting
-- in the slots are 2 5 3 4 1 and the penalty is |2-4| + |5-1| + |3-2| + |4-5| + |1-3| = 10, which is Poor.
--
-- M and S are permutations, so the ranks sitting in the slots are sigma = M ∘ P, one COMPOSE lookup, and the level is
-- GRADE[sigma][S], one more. GRADE holds all 120 x 120 pairs and is built once here. This rule reproduces every
-- graded row in a corpus of 25 recorded puzzles (535 arrangements).

local Permutations = require("src/core/permutations")
local Levels = require("src/core/levels")

-- A new array of n copies of value, as Java fills a new array with zeros or false.
local function filled(n, value)
    local array = {}
    for i = 1, n do
        array[i] = value
    end
    return array
end

local Scoring = {}

-- The level of the penalty between the permutations sigma and s, for every pair: row sigma, column s.
Scoring.GRADE = filled(Permutations.COUNT * Permutations.COUNT, 0)

for sigma = 1, Permutations.COUNT do
    local ranksInSlots = Permutations.list[sigma]
    local base = (sigma - 1) * Permutations.COUNT
    for s = 1, Permutations.COUNT do
        local slotRanks = Permutations.list[s]
        local penalty = 0
        for k = 1, Permutations.N do
            penalty = penalty + math.abs(ranksInSlots[k] - slotRanks[k])
        end
        Scoring.GRADE[base + s] = Levels.fromPenalty(penalty)
    end
end

-- The arrangement that scores Perfect for these ranks: M[P[s]] = S[s], so P is the inverse of M composed with S.
---@param moduleRanks integer permutation index of the module ranks
---@param slotRanks integer permutation index of the slot ranks
---@return integer arrangement permutation index
function Scoring.perfectArrangement(moduleRanks, slotRanks)
    return Permutations.COMPOSE[(Permutations.INV[moduleRanks] - 1) * Permutations.COUNT + slotRanks]
end

return Scoring
