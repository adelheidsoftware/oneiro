-- Streams with more steps give what Java's streams and Kotlin's collection functions give.

local Rankings = require("src/rankings")

local drops = {
    { item = "bones", count = 1, price = 100 },
    { item = "coins", count = 500, price = 1 },
    { item = "rune bar", count = 3, price = 12000 },
    { item = "ashes", count = 2, price = 50 },
}

assert(table.concat(Rankings.firstItems(drops, 2), ",") == "bones,coins", "limit after map")
assert(table.concat(Rankings.firstHalf(drops), ",") == "bones,coins", "limit worked out once")
assert(table.concat(Rankings.laterItems(drops), ",") == "coins,rune bar,ashes", "skip")
local most = Rankings.mostValuable(drops)
assert(#most == 2 and most[1].item == "rune bar" and most[2].item == "bones", "a sort, then limit")
assert(drops[1].item == "bones", "the sort sorts a copy, not the list")
assert(table.concat(Rankings.stackItems(drops), ",") == "ashes,rune bar,coins", "filter, a sort, then map")
assert(table.concat(Rankings.keepers(drops), ",") == "coins,rune bar", "a filter in steps")
assert(Rankings.lines(drops)[3] == "3x rune bar", "a map in steps")
local singles = Rankings.singles({ drops, { drops[1] }, {} })
assert(#singles == 3 and singles[1] == 1 and singles[2] == 1 and singles[3] == 0, "a stream inside a stream's lambda")
