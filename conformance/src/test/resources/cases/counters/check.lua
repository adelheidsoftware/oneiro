-- Integer division gives Java's results, with Lua's // only where the values cannot be negative.

local Counters = require("src/counters")
local Boosted = require("src/boosted")

local counters = Counters.new()
for _ = 1, 250 do
    counters:tick()
end
assert(counters:minutes() == 2, "250 ticks are 2 whole minutes")
assert(counters:report() == "0h 2m", "the report: " .. counters:report())
assert(counters:halves({ -3, 4 }) == 3, "half of the absolute values")
counters:spend(25)
assert(counters:balanceTens() == -2, "a negative balance divides toward zero, as in Java")
counters.shared = -15
assert(counters:sharedTens() == -1, "so does a field anyone may set")

local boosted = Boosted.new()
for _ = 1, 250 do
    boosted:tick()
end
assert(boosted:level() == -5, "the subclass's level")
assert(boosted:levelHalves() == -2, "a call that may run the override divides toward zero")
