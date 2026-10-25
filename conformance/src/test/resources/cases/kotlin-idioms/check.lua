-- Kotlin's idioms behave in Lua as they do in Kotlin.

local logged = {}
function log(...)
    logged[#logged + 1] = table.concat({ ... }, " ")
end

local printed = {}
function print(text)
    printed[#printed + 1] = text
end

local Idioms = require("src/idioms")

local passing = Idioms.passing({ 1, 3, 5 }, Idioms.AtLeast.new(3))
assert(#passing == 2 and passing[1] == 3, "a class's object is called as the function a Test is")
passing = Idioms.passing({ -1, 0, 2 }, Idioms.positive())
assert(#passing == 1 and passing[1] == 2, "a lambda is a Test too")
assert(Idioms.small(3) and not Idioms.small(30), "a Predicate made from a lambda")

assert(Idioms.level(50) == 50, "a level that passes both checks")
local ok, message = pcall(Idioms.level, 0)
assert(not ok and message:find("level 0", 1, true), "require fails with its message")
ok, message = pcall(Idioms.level, 99)
assert(not ok and message:find("Check failed.", 1, true), "check fails with Kotlin's message")

assert(Idioms.herbName({ [5] = "guam" }, 5) == "guam", "requireNotNull gives the value")
ok, message = pcall(Idioms.herbName, {}, 7)
assert(not ok and message:find("no herb 7", 1, true), "requireNotNull fails with its message")
assert(Idioms.seed({ guam = 5291 }, "guam") == 5291, "requireNotNull as a statement, and checkNotNull as a value")
ok, message = pcall(Idioms.seed, {}, "guam")
assert(not ok and message:find("Required value was null.", 1, true), "requireNotNull fails with Kotlin's message")
assert(Idioms.enabled(false) == false and Idioms.enabled(true) == true, "?: error() keeps a false")
ok, message = pcall(Idioms.enabled, nil)
assert(not ok and message:find("The setting is unknown.", 1, true), "?: error() fails on nil")

local counts = { 0 }
local work = Idioms.counter(counts)
work()
work()
assert(counts[1] == 2, "a Runnable made from a lambda is the function")

local trimmed = Idioms.trimmed({ "  guam ", "ranarr" })
assert(trimmed[1] == "guam" and printed[1] == "guam" and printed[2] == "ranarr", "String::trim and ::println")

local known = Idioms.known({ "guam", "torstol" }, { guam = true })
assert(known[1] == true and known[2] == false, "a set's contains")

local levels = {}
for herb, level in pairs(Idioms.levels()) do
    levels[#levels + 1] = herb .. "=" .. level
end
assert(table.concat(levels, ",") == "guam=3,marrentill=5", "mapOf keeps the order written")

local patches = {}
for patch in pairs(Idioms.patches()) do
    patches[#patches + 1] = patch
end
assert(table.concat(patches, ",") == "falador,catherby", "setOf keeps the order written")

local prices = Idioms.prices()
assert(prices.guam == 10 and prices.ranarr == 7000, "hashMapOf is a plain table")

assert(Idioms.beforeNegative({ 1, 2, -1, 5 }) == 3, "a labelled break of the innermost loop")
assert(Idioms.Steps.new():total(2) == 7, "a private companion's members are locals of the file")
assert(Idioms.asText("guam") == "guam", "as gives the value as it is")
assert(Idioms.textOrNull("guam") == "guam" and Idioms.textOrNull(5) == nil, "as? gives nil for another type")
Idioms.ticker()(1)
assert(logged[1] == "tick", "a lambda that never reads its parameter")
