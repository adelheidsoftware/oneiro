-- Method references are functions, opened where they are called.

local Refs = require("src/refs")

local lengths = Refs.lengths({ "ab", "cde" })
assert(lengths[1] == 2 and lengths[2] == 3, "String::length")
assert(Refs.filled({ "a", "", "b" }) == 2, "Objects::nonNull and a lambda, as one test")
assert(Refs.seeds({ "guam" })[1].name == "guam", "a record's constructor")
assert(Refs.names({ { name = "ranarr" } })[1] == "ranarr", "a record's accessor")
assert(Refs.fill({ "a", "b", "c" }) == 3, "a bound method of the plugin's")

local numbers = Refs.numbers({ "4", "15" })
assert(numbers[1] == 4 and numbers[2] == 15 and math.type(numbers[2]) == "integer", "Integer::parseInt")

local copy = Refs.copied({ "x", "y" })
assert(#copy == 2 and copy[2] == "y", "list::add")
Refs.print({})
assert(Refs.shouted({ "hi" })[1] == "HI", "String::toUpperCase")
local long = Refs.longNames({ "ash", "guam", "ranarr" })
assert(#long == 2 and long[1] == "guam" and long[2] == "ranarr", "list::add at the end of a stream")
