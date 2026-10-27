-- Objects called as functions give what Kotlin's invoke gives.

local Invokes = require("src/invokes")

local tenfold = function(value)
    return value * 10
end
local machine = Invokes.Machine.new(Invokes.Doubler.new(), Invokes.Stepper.new(3), tenfold)
-- 8 + 8 from the locals, 8 + 7 + 40 from the members, 5 + 3 from the others
assert(machine:run(4) == 79, "each call of an object from a method, none of them on the method's own object")
assert(Invokes.through(machine, 4) == 55, "each call of a machine's member from outside it")
assert(Invokes.Doubler.new()(5) == 10, "an object of a function type, called as library code calls it")
