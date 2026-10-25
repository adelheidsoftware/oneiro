-- Each chain gives what Kotlin's collection functions give.

local Loot = require("src/loot")

local function drop(item, count)
    return { item = item, count = count }
end

local drops = { drop("bones", 1), drop("coins", 500), drop("bones", 2), drop("rune bar", 3), drop("ashes", 2) }

local items = Loot.items(drops)
assert(table.concat(items, ",") == "bones,coins,rune bar,ashes", "distinct keeps the first of each, in order")

local firsts = Loot.firstOfEach(drops)
assert(#firsts == 4 and firsts[1] == drops[1] and firsts[3] == drops[4], "distinctBy keeps the first with each key")

local stacks = Loot.firstStacks(drops)
assert(#stacks == 3 and stacks[1] == drops[2] and stacks[3] == drops[4], "filter, then take")
assert(#Loot.firstStacks({}) == 0, "take of nothing")

local half = Loot.firstHalf(drops)
assert(#half == 2 and half[2] == drops[2], "take with a count worked out once")

local after = Loot.after(drops, 3)
assert(table.concat(after, ",") == "rune bar,ashes", "drop, then map")
assert(#Loot.after(drops, 10) == 0, "drop of more than there are")

local before = Loot.beforeCoins(drops)
assert(#before == 1 and before[1] == drops[1], "takeWhile stops at the first that fails")
local from = Loot.fromCoins(drops)
assert(#from == 4 and from[1] == drops[2] and from[2] == drops[3], "dropWhile keeps all from the first that fails")

local kills = {
    { monster = "goblin", drops = { drop("bones", 1), drop("coins", 5) } },
    { monster = "cow", drops = {} },
    { monster = "giant", drops = { drop("big bones", 1), drop("coins", 30), drop("ashes", 1), drop("rune bar", 1) } },
}
local all = Loot.allDrops(kills)
assert(#all == 6 and all[1].item == "bones" and all[6].item == "rune bar", "flatMap")
local five = Loot.firstFive(kills)
assert(#five == 5 and five[5].item == "ashes", "take after flatMap leaves both loops")
assert(Loot.dropped(kills, "ashes") and not Loot.dropped(kills, "logs"), "any after flatMap")
assert(table.concat(Loot.tens(3), ",") == "1,10,2,20,3,30", "flatMap over a range")
assert(Loot.firstRare(kills, { ashes = true, ["rune bar"] = true }) == "ashes" and Loot.firstRare(kills, {}) == nil,
    "firstOrNull after flatMap stops both loops")

local byItem = Loot.byItem(drops)
local keys = {}
for item, group in pairs(byItem) do
    keys[#keys + 1] = item .. "=" .. #group
end
assert(table.concat(keys, ",") == "bones=2,coins=1,rune bar=1,ashes=1", "groupBy keeps the order keys came in")
assert(byItem.bones[2] == drops[3], "groupBy lists the elements with each key")
local counts = Loot.countsByItem(drops)
assert(counts.bones[1] == 1 and counts.bones[2] == 2, "groupBy with a value function")

local last = Loot.counts(drops)
assert(last.bones == 2 and last.coins == 500, "associate: a later pair replaces an earlier one")
local byMonster = Loot.byMonster(kills)
assert(byMonster.cow == kills[2], "associateBy")
local lengths = Loot.nameLengths({ "guam", "ranarr" })
assert(lengths.guam == 4 and lengths.ranarr == 6, "associateWith")
