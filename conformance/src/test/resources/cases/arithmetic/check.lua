-- Java's results for each operation, worked out by hand from the Java Language Specification.
local Arithmetic = require("src/arithmetic")

assert(Arithmetic.divide(-7, 2) == -3)
assert(Arithmetic.divide(7, -2) == -3)
assert(Arithmetic.divide(-8, 2) == -4)
assert(Arithmetic.divide(7, 2) == 3)
assert(Arithmetic.remainder(-7, 2) == -1)
assert(Arithmetic.remainder(7, -2) == 1)
assert(Arithmetic.half({ 1, 2, 3 }) == 2)
-- -32 >> 4 is -2; -32 >>> 28 is 15 (0xFFFFFFE0 shifted); -32 << 1 is -64; 5 >> 1 is 2
assert(Arithmetic.shifted(-32) == -2 + 15 - 64 + 2)
assert(Arithmetic.ratio(1, 4.0) == 0.25)
assert(Arithmetic.truncated(-2.7) == -2)
assert(Arithmetic.truncated(2.7) == 2)
-- Math.floorDiv(-7, 2) is -4 and Math.floorMod(-7, 2) is 1
assert(Arithmetic.floored(-7, 2) == -3)
