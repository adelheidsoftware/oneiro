-- An enum with fields, a constructor and methods has constants that are objects.

local Herb = require("src/herb")
local Stage = require("src/stage")
local Patches = require("src/patches")
local Tier = require("src/tier")

assert(Herb.GUAM.name == "GUAM" and Herb.RANARR.ordinal == 2, "a constant knows its name and ordinal")
assert(Herb.MARRENTILL:canPlant(14) and not Herb.RANARR:canPlant(31), "fields set by the constructor")
assert(Herb.GUAM:describe() == "guam from seed 5291", "a method of the enum")
assert(Herb.RANARR:describe() == "the prayer herb", "a constant's body replaces the method")
assert(Herb.GUAM:next() == Herb.MARRENTILL and Herb.RANARR:next() == Herb.RANARR, "values() and ordinal()")
assert(Patches.plantable(20) == "GUAM MARRENTILL", "constants as text by their names")

local counts = Patches.counts({ Herb.GUAM, Herb.RANARR, Herb.GUAM })
assert(counts[Herb.GUAM] == 2 and counts[Herb.RANARR] == 1, "constants as map keys")

assert(Patches.saved("RANARR") == Herb.RANARR, "valueOf")
assert(Patches.earlier(Herb.GUAM, Herb.RANARR) and not Patches.earlier(Herb.RANARR, Herb.GUAM), "compareTo")
assert(Patches.step(Stage.DONE) == 2, "ordinal() makes a simple enum an object")
assert(Patches.look(Herb.RANARR, Stage.SEED) == "a seed of the prayer herb", "switch on an object enum")
assert(Patches.look(Herb.GUAM, Stage.DONE) == "GUAM ready", "a constant in text")

local tiers = Patches.easiestFirst({ Tier.ELITE, Tier.EASY, Tier.HARD })
assert(tiers[1] == Tier.EASY and tiers[2] == Tier.HARD and tiers[3] == Tier.ELITE, "a natural sort orders by declaration")
