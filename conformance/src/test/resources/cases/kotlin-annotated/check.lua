-- An annotation on an expression changes nothing the Lua does.

local Annotated = require("src/annotated")

local names = Annotated.names({ "a", "b" })
assert(#names == 2 and names[1] == "a", "an annotated cast, a local's value")
assert(#Annotated.namesOrNone({ "c" }) == 1, "an annotated cast, the value of a try")
assert(Annotated.high(60) and not Annotated.high(10), "an annotated if, a local's value")
