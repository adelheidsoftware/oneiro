-- A stand-in for the client's World: ground at height 100 west of x = 1000, none beyond, and lower to the east.
World = {
    GetGroundHeight = function(level, x, z)
        if x >= 1000 then
            return -1, false
        end
        return 100 - x // 10, true
    end,
}

local Heights = require("src/heights")

assert(Heights.groundOr(0, 0, 7) == 100, "the height where there is ground")
assert(Heights.groundOr(2000, 0, 7) == 7, "the fallback where there is none")
assert(Heights.lowest(0, 0) == 49, "the lower of two heights")

local low, high = Heights.span({ 4, -2, 9 })
assert(low == -2 and high == 9, "two values returned")
assert(Heights.width({ 4, -2, 9 }) == 11, "both read from a call")
local passedLow, passedHigh = Heights.spanOfAll({ 3, 5 })
assert(passedLow == 3 and passedHigh == 5, "passed through")
local checkedLow, checkedHigh = Heights.checkedSpan({ 1, 8 })
assert(checkedLow == 1 and checkedHigh == 8, "returned from the locals")
assert(not pcall(Heights.checkedSpan, { -1 }), "and checked first")

assert(Heights.report({ 1, 2, 3, 6 }) == "4 values, mean 3", "the hand-written module's two values")
assert(Heights.range({ 4, -2, 9 }) == 11, "a record declared in the module's declaration")
