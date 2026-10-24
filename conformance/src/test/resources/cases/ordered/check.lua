-- Ordered maps and sets walk their keys in Java's order.

local Ledger = require("src/ledger")

local drops = { "coins", "bones", "coins", "ashes", "bones" }
local names = Ledger.firstSeen(drops)
assert(#names == 3 and names[1] == "coins" and names[2] == "bones" and names[3] == "ashes", "LinkedHashSet order")
assert(Ledger.tally(drops) == "{coins=2, bones=2, ashes=1}", "a LinkedHashMap writes its entries in order")

local owed = Ledger.owed({ "coins", "bones", "ashes" }, "coins")
assert(#owed == 3 and owed[1] == "bones" and owed[2] == "ashes" and owed[3] == "coins", "re-added goes last")

assert(Ledger.sorted(drops) == "[ashes, bones, coins]", "a TreeSet walks its keys sorted")

local lines = Ledger.reversed({ ashes = 1, coins = 2, bones = 3 })
assert(#lines == 3 and lines[1] == "coins x2" and lines[2] == "bones x3" and lines[3] == "ashes x1", "TreeMap order")

assert(Ledger.ends(drops) == "ashes..coins", "a TreeSet copied from a list, and its first and last")

local merged = Ledger.merged({ "coins", "ashes" }, { bones = true, coins = true })
assert(#merged == 3 and merged[1] == "ashes" and merged[2] == "bones" and merged[3] == "coins", "copied, added, sorted")

local walked = {}
for drop, count in pairs(Ledger.combined({ coins = 2, bones = 1 }, { ashes = 4, coins = 3 })) do
    walked[#walked + 1] = drop .. "=" .. count
end
assert(table.concat(walked, " ") == "ashes=4 bones=1 coins=5", "a TreeMap copied from a map, merged into")

local restocked = Ledger.restocked({ coins = 1, bones = 2 }, { coins = 5 })
assert(restocked.coins == 5 and restocked.bones == 2, "a copy with putAll over it")

local either = Ledger.either({ coins = true }, { bones = true, coins = true })
assert(either.coins and either.bones and either.ashes == nil, "one set's keys added to another set")

local named = Ledger.named({ coins = true }, { [7] = "bones" })
assert(#named == 2 and named[1] == "coins" and named[2] == "bones", "a set's keys and a map's values added to a list")

assert(Ledger.firstAndLast({ "coins", "ashes", "bones" }) == "coins to bones", "getFirst and getLast")
