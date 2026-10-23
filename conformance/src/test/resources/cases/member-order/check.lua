-- The initializer ran after the functions it calls, and the mutually recursive pair finds each other.
local Order = require("src/order")

assert(table.concat(Order.SQUARES, ",") == "0,1,4,9")
assert(Order.isEven(4))
assert(not Order.isEven(3))
