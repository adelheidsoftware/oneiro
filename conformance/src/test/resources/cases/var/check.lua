-- Each method's result as the Java method computes it.
local Inferred = require("src/inferred")

assert(Inferred.sum({ 1, 2, 3 }) == 6)
assert(Inferred.sumBelow(5) == 10)
assert(Inferred.weighted({ ab = 2, c = 3 }) == 7)
local kept = Inferred.longNames({ "Ann", "Bertha", "Cy", "Dorothy" })
assert(#kept == 2 and kept[1] == "Bertha" and kept[2] == "Dorothy")
local lengths = Inferred.lengths({ "Ann", "Cy" })
assert(lengths.Ann == 3 and lengths.Cy == 2)
assert(Inferred.combined(2, 3) == 5)
assert(Inferred.measured("herb") == 4)
-- The reader was closed once, after it was read
assert(Inferred.Reader.closes == 1)
