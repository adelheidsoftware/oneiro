-- Each stream is one loop that gives Java's result.

local Herbs = require("src/herbs")

local harvests = {
    { herb = "guam", count = 6 },
    { herb = "ranarr", count = 0 },
    { herb = "snapdragon", count = 9 },
}

local good = Herbs.good(harvests, 5)
assert(#good == 2 and good[1] == "GUAM" and good[2] == "SNAPDRAGON", "filter, map and toList keep the order")
assert(Herbs.empty(harvests) == 1, "filter and count")
assert(Herbs.grew(harvests, "ranarr") and not Herbs.grew(harvests, "torstol"), "anyMatch returns from the loop")
assert(not Herbs.allGave(harvests) and Herbs.allGave({ harvests[1] }), "allMatch stops at the first failure")
assert(Herbs.allGave({}), "allMatch of nothing is true, as in Java")
assert(Herbs.firstWith({ "guam", "snapdragon", "spirit weed" }, "s") == "snapdragon", "findFirst finds the first")
assert(Herbs.firstWith({}, "s") == "none", "findFirst of nothing falls back")
assert(Herbs.total(harvests) == 15, "mapToInt and sum")
assert(Herbs.best(harvests) == 9 and Herbs.best({}) == 0, "max, or the fallback for nothing")
assert(Herbs.line({ "Guam", "Marrentill" }) == "guam, marrentill", "joining")

local numbers = {}
Herbs.countdown(3, numbers)
assert(#numbers == 3 and numbers[1] == 3 and numbers[3] == 1, "rangeClosed includes its end")

local ranked = Herbs.ranked(harvests)
assert(ranked[1].herb == "snapdragon" and ranked[3].herb == "ranarr", "sorted by a reversed comparator")
assert(harvests[1].herb == "guam", "sorted leaves the source as it was")

local stocked = Herbs.stocked({ guam = 2, ranarr = 0 })
assert(stocked.guam and stocked.ranarr == nil, "a map's keys, filtered into a set")

Herbs.byName(harvests)
assert(harvests[1].herb == "snapdragon" and harvests[3].herb == "guam", "list.sort with a reversed comparator")

local tied = { { herb = "guam", count = 5 }, { herb = "dwarf weed", count = 1 }, { herb = "guam", count = 2 } }
Herbs.byHerbThenCount(tied)
assert(tied[1].herb == "dwarf weed" and tied[2].count == 2 and tied[3].count == 5, "thenComparingInt breaks ties")

local long = Herbs.longNames({ "guam", "snapdragon" })
assert(#long == 1 and long[1] == "snapdragon", "a method reference as the test")
