-- The endgame search: given the current row and the surviving seeds, the swap that minimises the number of further
-- swaps needed in the worst case.
--
-- It asks, for depth 1, 2, 3 and so on, whether every survivor can be brought to Perfect within that many swaps; the
-- first depth that works is the bound reported with the move. At each position it tries the ten swaps, splits the
-- survivors by the level each swap would produce, and requires every outcome to be solvable one swap sooner. It runs
-- once 12 or fewer Perfect arrangements remain (see deduce.lua); with the opening table ahead of it, it reaches
-- Perfect within 11 swaps on every blueprint, which is the best any strategy can guarantee. A node budget bounds the
-- cost, and when it runs out the caller falls back to the greedy layer.

local Permutations = require("src/core/permutations")
local Seeds = require("src/core/seeds")

local Search = {}

-- Declared ahead: used above their definitions below.
local compare, solvable

-- For each number of graded swaps d, the most distinct answers any strategy can tell apart in d swaps. A swap is
-- answered by one of five levels other than Perfect, each leading to a position with d - 1 swaps left, and by
-- Perfect, which finishes exactly one answer. So the value for d is 1 + 5 times the value for d - 1: 1, 6, 31,
-- 156, ... A position with more answers than that cannot be finished in d swaps, whatever is tried.
local RESOLVABLE = {}

RESOLVABLE[0] = 0
for d = 1, 20 do
    RESOLVABLE[d] = 1 + 5 * RESOLVABLE[d - 1]
end

-- The message of the error raised when the node budget runs out, which find catches.
local NODE_BUDGET_SPENT = "search: node budget spent"

local nodes = 0
local nodeCap = 0

-- The swaps from the arrangement worth trying at this depth, best first, or the one that makes every survivor
-- Perfect. A swap is dropped when either bound says it cannot finish in depth - 1 more swaps.
---@param arrangement integer
---@param survivors integer[]
---@param depth integer
---@return table[] cands
---@return table? immediate
local function candidates(arrangement, survivors, depth)
    local cands = {}
    for _, swap in ipairs(Permutations.TRANSPOSITIONS) do
        local swapped = Permutations.COMPOSE[(arrangement - 1) * Permutations.COUNT + swap.index]
        local counts, answers, farthest = Seeds.score(survivors, swapped)
        local worstAnswers = 0
        local worstSeeds = 0
        local done = true
        for level = 1, 5 do
            if counts[level] > 0 then
                done = false
                worstAnswers = math.max(worstAnswers, answers[level])
                worstSeeds = math.max(worstSeeds, counts[level])
            end
        end
        if done then
            return {}, {
                swap = swap,
                swapped = swapped,
                worstAnswers = 0,
                worstSeeds = 0,
                farthest = 0,
                counts = counts,
            }
        end
        if worstAnswers <= RESOLVABLE[depth - 1] and farthest <= depth - 1 then
            cands[#cands + 1] = {
                swap = swap,
                swapped = swapped,
                worstAnswers = worstAnswers,
                worstSeeds = worstSeeds,
                farthest = farthest,
                counts = counts,
            }
        end
    end
    table.sort(cands, function(x, y)
        return compare(x, y) < 0
    end)
    return cands, nil
end

-- The order candidates are tried in: fewest answers in the worst outcome first, then the nearest farthest answer,
-- then the fewest seeds in the worst outcome, and the earlier slots last, so that the order is the same on every
-- run.
---@param x table
---@param y table
---@return integer
function compare(x, y)
    if x.worstAnswers ~= y.worstAnswers then
        return x.worstAnswers - y.worstAnswers
    end
    if x.farthest ~= y.farthest then
        return x.farthest - y.farthest
    end
    if x.worstSeeds ~= y.worstSeeds then
        return x.worstSeeds - y.worstSeeds
    end
    if x.swap.i ~= y.swap.i then
        return x.swap.i - y.swap.i
    end
    return x.swap.j - y.swap.j
end

-- Whether every level the candidate can produce leaves survivors that are solvable in depth - 1 swaps.
---@param cand table
---@param survivors integer[]
---@param depth integer
---@return boolean
local function everyOutcomeSolvable(cand, survivors, depth)
    local buckets = Seeds.split(survivors, cand.swapped)
    for level = 1, 5 do
        if cand.counts[level] > 0 and not solvable(cand.swapped, buckets[level], depth - 1) then
            return false
        end
    end
    return true
end

-- Can every survivor be brought to Perfect within depth swaps from the arrangement?
---@param arrangement integer
---@param survivors integer[]
---@param depth integer
---@return boolean
function solvable(arrangement, survivors, depth)
    if #survivors == 0 then
        return true
    end
    if depth == 0 then
        return false
    end
    if Seeds.distinctAnswers(survivors) > RESOLVABLE[depth] then
        return false
    end
    if Seeds.farthest(arrangement, survivors) > depth then
        return false
    end
    nodes = nodes + 1
    if nodes > nodeCap then
        error(NODE_BUDGET_SPENT)
    end
    local cands, immediate = candidates(arrangement, survivors, depth)
    if immediate ~= nil then
        return true
    end
    if depth == 1 then
        return false
    end
    for _, cand in ipairs(cands) do
        if everyOutcomeSolvable(cand, survivors, depth) then
            return true
        end
    end
    return false
end

-- The first candidate, in preference order, whose every outcome is solvable in depth - 1 swaps.
---@param arrangement integer
---@param survivors integer[]
---@param depth integer
---@return table
local function choose(arrangement, survivors, depth)
    local cands, immediate = candidates(arrangement, survivors, depth)
    if immediate ~= nil then
        return immediate
    end
    for _, cand in ipairs(cands) do
        if everyOutcomeSolvable(cand, survivors, depth) then
            return cand
        end
    end
    error("search: no feasible move at a depth that was just proven solvable")
end

---@param current integer arrangement index of the row as it stands
---@param survivors integer[] surviving seeds, none of which has current as its answer
---@param cap integer most nodes to expand before giving up
---@return table? move the slots to swap and the most swaps still needed; nil when the cap was hit
function Search.find(current, survivors, cap)
    nodes = 0
    nodeCap = cap
    local ok, result = pcall(function()
        local depth = math.max(Seeds.farthest(current, survivors), 1)
        while not solvable(current, survivors, depth) do
            depth = depth + 1
        end
        local swap = choose(current, survivors, depth).swap
        return { i = swap.i, j = swap.j, bound = depth }
    end)
    if ok then
        return result
    else
        local e = result
        -- The message of a Lua error starts with the place it was raised
        if e:find(NODE_BUDGET_SPENT, 1, true) ~= nil then
            return nil
        end
        error(e, 0)
    end
end

return Search
