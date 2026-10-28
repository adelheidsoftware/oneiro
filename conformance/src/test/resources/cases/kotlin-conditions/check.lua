-- Chains inside conditions and other expressions behave as Kotlin's do.

local Conditions = require("src/conditions")

local drops = { { item = "coins", count = 50 }, { item = "rare", count = 1 } }
assert(Conditions.rarity(drops) == "rare", "any in an if's condition")
assert(Conditions.rarity({ { item = "coins", count = 5 } }) == "common", "and when nothing matches")
assert(Conditions.stacksAndOne(drops) == 2, "a count inside arithmetic")
assert(Conditions.dropped(drops, "rare") and not Conditions.dropped(drops, "bones"), "any after &&")
assert(not Conditions.dropped(drops, nil), "&& gives false when its left side is false")
assert(Conditions.allStacks(drops, false), "|| gives true when its left side is true")
assert(not Conditions.allStacks(drops, true), "all after ||")
assert(Conditions.total(drops) == "Total: 51", "a sum inside text")
assert(Conditions.calm(true, 1) and not Conditions.calm(true, 2), "a when after &&")
assert(not Conditions.calm(false, 1), "&& gives false without running the when")
assert(Conditions.countOf({ item = "logs", count = 4 }) == 4, "a data class that is not null")
assert(Conditions.countOf(nil) == 0, "a data class that is null")
assert(Conditions.missing(nil) and not Conditions.missing({ item = "logs", count = 1 }), "null on the left")
assert(Conditions.paired({ "logs", 1 }) and not Conditions.paired(nil), "a pair compared with null")
assert(Conditions.isLevel(99) and not Conditions.isLevel(100), "in a range")
assert(Conditions.fiveIsLevel(), "a literal in a range")
