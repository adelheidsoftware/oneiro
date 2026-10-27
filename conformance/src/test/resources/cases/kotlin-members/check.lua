-- Secondary constructors and extension properties behave as Kotlin's do.

local Members = require("src/members")

local tally = Members.empty("herbs")
assert(tally.name == "herbs" and tally.total == 0, "a secondary constructor that gives a default")
tally:add(5)
assert(tally.total == 5, "an object a secondary constructor made")
assert(Members.Tally.new("ores", 7).total == 7, "the primary constructor")
assert(Members.counter().count == 6, "a sole secondary constructor")
assert(Members.initialOf("Zezima") == "Z", "an extension property")
assert(Members.hasMany({ 1, 2 }) and not Members.hasMany({ 1 }), "a private extension property with a block getter")
