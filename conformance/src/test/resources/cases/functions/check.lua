local Functions = require("src/functions")

local evens = Functions.evens({ 1, 2, 3, 4 })
assert(#evens == 2 and evens[1] == 2 and evens[2] == 4)
assert(Functions.evenCount({ 1, 2, 4 }) == 2)
assert(Functions.matching({ 5, 6 }, function(value)
    return value > 5
end) == 1, "a plugin interface with one method is a plain function")
assert(Functions.applied(3) == 15, "twice(3) plus the length of \"functions\"")
local found = Functions.collected({ 4, 5 })
assert(#found == 2 and found[2] == 5, "list::add given to a plugin interface whose method returns nothing")
