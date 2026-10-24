-- Each loop's result as the Java method computes it.
local Loops = require("src/loops")

assert(Loops.sumBelow(5) == 10)
assert(Loops.countDown(3) == 3)
assert(Loops.evens(6) == 4)
assert(Loops.sumOf({ 1, 2, 3 }) == 6)
assert(Loops.weighted({ 1, 2, 3 }) == 8)
assert(Loops.skipOdd({ 1, 2, 3, 4 }) == 6)
-- Two pairs per i from the inner loop, and one more for every i but the first
assert(Loops.pairs(3) == 8)
assert(Loops.halvings(8) == 3)
assert(Loops.keySum({ a = 1, bb = 2 }, { a = true }) == 2)
-- Only the cells whose column is their own row count
assert(Loops.diagonal({ a = { a = 1, b = 2 }, b = { b = 3 }, c = { a = 4 } }) == 4)
assert(Loops.drain(3) == 0)
