-- Each chain gives what Kotlin's collection functions give.

local logged = {}
function log(...)
    logged[#logged + 1] = table.concat({ ... }, " ")
end

local Drops = require("src/drops")

local drops = {
    { item = "bones", count = 1, price = 100.0 },
    { item = "coins", count = 500, price = 1.0 },
    { item = "rune bar", count = 3, price = 12000.0 },
    { item = "ashes", count = 2, price = 50.0 },
}

local items = Drops.items(drops)
assert(#items == 3 and items[1] == "bones" and items[3] == "ashes", "filterNot, then map")

local worths = Drops.worths(drops, { coins = true, ashes = true })
assert(#worths == 2 and worths[1] == 500.0 and worths[2] == 100.0, "mapNotNull leaves out the nulls")

assert(Drops.worth(drops) == 100.0 + 500.0 + 36000.0 + 100.0, "sumOf")
assert(math.type(Drops.worth({})) == "float", "sumOf of decimals starts from 0.0")
assert(Drops.best(drops).item == "rune bar" and Drops.best({}) == nil, "maxByOrNull")
assert(Drops.smallest(drops) == 1, "minOf")
assert(Drops.biggest(drops).item == "coins", "maxWithOrNull with a comparator")
assert(Drops.find(drops, "ashes") == drops[4] and Drops.find(drops, "logs") == nil, "find")
assert(Drops.lastWorth(drops, 60.0) == drops[3], "lastOrNull keeps the last that passes")
assert(Drops.missed(drops, "logs") and not Drops.missed(drops, "coins"), "none")
assert(Drops.stacks(drops) == 3, "filter, then count")
assert(Drops.line(drops) == "[bones, coins, rune bar, ashes]", "joinToString with a prefix and a postfix")
assert(Drops.counts(drops) == "1 + 500 + 3 + 2", "joinToString with a transform")

local kinds = Drops.kinds({ drops[2], drops[1], drops[2] })
local order = {}
for kind in pairs(kinds) do
    order[#order + 1] = kind
end
assert(#order == 2 and order[1] == "coins" and order[2] == "bones", "toSet keeps the order the items came in")

assert(table.concat(Drops.names(drops), ",") == "rune bar,coins,bones,ashes", "sortedDescending")
local ranked = Drops.ranked(drops)
assert(ranked[1].item == "rune bar" and ranked[2].item == "coins", "compareByDescending")
assert(ranked[3].item == "ashes" and ranked[4].item == "bones", "thenBy breaks the tie")
assert(drops[1].item == "bones", "sortedWith leaves the list as it was")

local stacks = Drops.stacksByItem(drops)
assert(#stacks == 3 and stacks[1].item == "ashes" and stacks[3].item == "rune bar", "filter, then sortedBy")

local copy = { drops[1], drops[2], drops[3], drops[4] }
Drops.sortByCount(copy)
assert(copy[1].item == "coins" and copy[4].item == "bones", "sortByDescending in place")

local squares = Drops.squares(3)
assert(#squares == 3 and squares[3] == 9, "a range, mapped")
assert(table.concat(Drops.above(5, 2), ",") == "5,4,3", "a range down to 1, filtered")
assert(Drops.stocked({ guam = 2, ranarr = 3 }) == 5, "a map's values, summed")

local long = Drops.longNames({ guam = 1, snapdragon = 2, torstol = 3 }, 5)
table.sort(long)
assert(#long == 2 and long[1] == "snapdragon" and long[2] == "torstol", "a map's keys, filtered")

local valuable = Drops.valuable(drops, 400.0)
assert(#valuable == 2 and valuable[1] == 500.0 and valuable[2] == 36000.0, "map, then filter on the value")

Drops.logWorth(drops, 1000.0)
assert(#logged == 1 and logged[1] == "worth it: rune bar", "filter, then forEach")
assert(Drops.firstWorth(drops, 1000.0) == "rune bar", "firstOrNull")
assert(Drops.firstWorth(drops, 99999.0) == "nothing", "firstOrNull, or a return")
assert(Drops.mostOf(drops, "coins") == 500 and Drops.mostOf(drops, "logs") == 0, "maxOfOrNull, or 0, assigned")

local most = Drops.mostValuable(drops)
assert(#most == 2 and most[1].item == "rune bar" and most[2].item == "bones", "a sort, then take")
assert(drops[1].item == "bones", "the sort sorts a copy, not the list")
local stacks = Drops.stackItems(drops)
assert(table.concat(stacks, ",") == "ashes,rune bar,coins", "filter, a sort, then map")
local cheap = Drops.cheapest(drops)
assert(#cheap == 2 and cheap[1].item == "coins" and cheap[2].item == "ashes", "a sort by a comparator, then take")

Drops.logItems(drops, 1000.0)
assert(logged[#logged] == "rune bar", "filter and map, joined as an argument")
assert(Drops.looks == #drops, "a chain joined as an argument looks at each element once")
