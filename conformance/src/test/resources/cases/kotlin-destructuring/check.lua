-- Pairs and destructuring behave as Kotlin's do.

local Pairs = require("src/pairs")

local entry = Pairs.entry("Zezima", 99)
assert(entry.first == "Zezima" and entry.second == 99, "a pair made with to")
assert(Pairs.describe(entry) == "Zezima 99", "a pair's first and second")
local ordered = Pairs.ordered(7, 3)
assert(ordered.first == 3 and ordered.second == 7, "a pair made with Pair")
assert(Pairs.level(entry) == 99, "destructuring a pair, leaving a part out")
assert(Pairs.difference(3, 10) == 7, "destructuring a pair made in place")
assert(Pairs.gap(10, 4) == 6 and Pairs.gap(4, 10) == 6, "destructuring a pair from a call")
assert(Pairs.label({ item = "bones", count = 3 }) == "3 x bones", "destructuring a data class")
assert(Pairs.firstTwo({ "a", "b", "c" }) == "a, b", "destructuring a list")

local drops = { { item = "coins", count = 50 }, { item = "rare", count = 1 }, { item = "coins", count = 6 } }
assert(Pairs.split(drops) == "2 stacks, 1 singles", "partition")
assert(Pairs.lines({ { first = "a", second = 1 }, { first = "b", second = 2 } }) == "a: 1\nb: 2\n",
    "a loop that destructures pairs")
assert(Pairs.countOf(drops, "coins") == 56, "a loop that destructures data classes")
assert(Pairs.numbered({ "a", "b" }) == "1. a 2. b ", "withIndex counts from 0")
local before = Pairs.before({ "a", "b", "c" }, 2)
assert(#before == 2 and before[2] == "b", "withIndex's position")
assert(Pairs.withLevels({ "a", "b" }, { 5, 7 }) == "a 5;b 7;", "forEachIndexed")
local positions = Pairs.positions({ "a", "b" })
assert(positions[1] == "0=a" and positions[2] == "1=b", "mapIndexed counts from 0")

local function walked(map)
    local parts = {}
    for key, value in pairs(map) do
        parts[#parts + 1] = key .. "=" .. value
    end
    return table.concat(parts, ",")
end

assert(walked(Pairs.counts(drops)) == "coins=6,rare=1", "toMap of pairs a map makes, a later pair winning")
assert(walked(Pairs.levels({ { first = "b", second = 2 }, { first = "a", second = 1 } })) == "b=2,a=1",
    "toMap keeps the order")
