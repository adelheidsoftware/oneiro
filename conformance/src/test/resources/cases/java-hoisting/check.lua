-- Values read once into a local, where only Java has them, give what Java gives.

local Patterns = require("src/patterns")

assert(Patterns.settingLength("mode") == 4 and Patterns.settingLength("level") == -1,
    "a call's value tested by a pattern")
local labelled = Patterns.labelled({ "a", "b" }, "#")
assert(#labelled == 2 and labelled[2] == "# b", "a reference to a method of a call's value")
assert(Patterns.readyAndLong(true, "guam leaf") and not Patterns.readyAndLong(false, "guam leaf"), "& of a call")
assert(Patterns.checkCount() == 2, "& checks the name either way")
