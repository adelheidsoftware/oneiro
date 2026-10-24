-- src/core/permutations.lua
-- Arrangements of the five Discovery modules as permutations. An arrangement maps slot to module, both 1..5, so
-- 2 1 3 4 5 means module 2 sits in slot 1 and module 1 in slot 2. Module n is whatever sat in slot n of the first
-- graded row, so that row is always 1 2 3 4 5, the first entry of `list`. The rest of the engine refers to an
-- arrangement by its index in `list`, counting from 1, and the three tables built here let the hot loops in seeds.lua
-- and search.lua work on integers only:
-- - COMPOSE is a ∘ b, applying b and then a. Swapping slots i and j of arrangement c is c ∘ t, where t is the
--   arrangement that only exchanges slots i and j; scoring.lua and seeds.lua also use it to put a seed's ranks into an
--   arrangement's slots.
-- - INV is the inverse of an arrangement, for DIST below and for the Perfect arrangements in scoring.lua.
-- - DIST is the smallest number of swaps that turns one arrangement into another: 1 2 3 4 5 to 2 1 3 4 5 is one swap,
--   to 2 3 1 4 5 two, and nothing is more than four away.

-- A new array of n copies of value, as Java fills a new array with zeros or false.
local function filled(n, value)
    local array = {}
    for i = 1, n do
        array[i] = value
    end
    return array
end

local Permutations = {}

Permutations.N = 5

-- Every arrangement in lexicographic order, so the first is the identity 1 2 3 4 5.
Permutations.list = {}
local indexByKey = {}

local function keyOf(p)
    return p[1] * 10000 + p[2] * 1000 + p[3] * 100 + p[4] * 10 + p[5]
end

-- Adds every arrangement that starts with the prefix, in lexicographic order.
---@param prefix integer[]
---@param remaining integer[]
local function generate(prefix, remaining)
    if #remaining == 0 then
        local p = { prefix[1], prefix[2], prefix[3], prefix[4], prefix[5] }
        Permutations.list[#Permutations.list + 1] = p
        indexByKey[keyOf(p)] = #Permutations.list
        return
    end
    for i = 1, #remaining do
        prefix[#prefix + 1] = remaining[i]
        local rest = {}
        for j = 1, #remaining do
            if j ~= i then
                rest[#rest + 1] = remaining[j]
            end
        end
        generate(prefix, rest)
        table.remove(prefix, #prefix)
    end
end

generate({}, { 1, 2, 3, 4, 5 })

Permutations.COUNT = #Permutations.list -- 120

---@param arrangement integer[] slot -> module
---@return integer? index into Permutations.list, nil unless the array is a permutation of 1..5
function Permutations.indexOf(arrangement)
    if #arrangement ~= Permutations.N then
        return nil
    end
    for s = 1, Permutations.N do
        local module = arrangement[s]
        if module < 1 or module > Permutations.N then
            return nil
        end
    end
    return indexByKey[keyOf(arrangement)]
end

-- The index of a ∘ b, where (a ∘ b)[s] = a[b[s]], for every pair: row a, column b.
Permutations.COMPOSE = filled(Permutations.COUNT * Permutations.COUNT, 0)
-- The index of the inverse of each arrangement.
Permutations.INV = filled(Permutations.COUNT, 0)

for a = 1, Permutations.COUNT do
    local pa = Permutations.list[a]
    local inverse = filled(Permutations.N, 0)
    for s = 1, Permutations.N do
        inverse[pa[s]] = s
    end
    Permutations.INV[a] = indexByKey[keyOf(inverse)]
    local base = (a - 1) * Permutations.COUNT
    for b = 1, Permutations.COUNT do
        local pb = Permutations.list[b]
        local composed = { pa[pb[1]], pa[pb[2]], pa[pb[3]], pa[pb[4]], pa[pb[5]] }
        Permutations.COMPOSE[base + b] = indexByKey[keyOf(composed)]
    end
end

-- Swapping slots i and j of arrangement c gives c ∘ t, with t the transposition of i and j.
Permutations.TRANSPOSITIONS = {}
local transpositionAt = {}

for i = 1, Permutations.N - 1 do
    transpositionAt[i] = {}
    for j = i + 1, Permutations.N do
        local t = { 1, 2, 3, 4, 5 }
        t[i] = j
        t[j] = i
        Permutations.TRANSPOSITIONS[#Permutations.TRANSPOSITIONS + 1] = { i = i, j = j, index = indexByKey[keyOf(t)] }
        transpositionAt[i][j] = #Permutations.TRANSPOSITIONS
    end
end

---@param arrangement integer arrangement index
---@param i integer slot
---@param j integer slot, in either order relative to i
---@return integer arrangement index after swapping the two slots
function Permutations.swap(arrangement, i, j)
    if i > j then
        local first = j
        j = i
        i = first
    end
    local transposition = Permutations.TRANSPOSITIONS[transpositionAt[i][j]].index
    return Permutations.COMPOSE[(arrangement - 1) * Permutations.COUNT + transposition]
end

local function cycleCount(p)
    local seen = filled(Permutations.N, false)
    local count = 0
    for s = 1, Permutations.N do
        if not seen[s] then
            count = count + 1
            -- Follow the cycle from slot s; the modules in p count from 1
            local k = s
            while not seen[k] do
                seen[k] = true
                k = p[k]
            end
        end
    end
    return count
end

-- The number of swaps that turn arrangement a into b, for every pair: row a, column b. The rearrangement a⁻¹ ∘ b
-- splits into cycles (a module already in place is a cycle of one), and a cycle of length k takes k - 1 swaps, so
-- the total is N minus the number of cycles.
Permutations.DIST = filled(Permutations.COUNT * Permutations.COUNT, 0)

for a = 1, Permutations.COUNT do
    local base = (a - 1) * Permutations.COUNT
    local invBase = (Permutations.INV[a] - 1) * Permutations.COUNT
    for b = 1, Permutations.COUNT do
        Permutations.DIST[base + b] = Permutations.N - cycleCount(Permutations.list[Permutations.COMPOSE[invBase + b]])
    end
end

return Permutations
