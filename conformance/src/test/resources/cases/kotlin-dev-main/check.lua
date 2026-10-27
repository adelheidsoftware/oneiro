-- Dev code calls the main source set's top-level functions in the main set's module.

local Cheats = require("dev/cheats")

assert(Cheats.clamped() == 99, "a top-level function of the main set's")
assert(Cheats.text(5) == "level 5", "a top-level extension function of the main set's")
