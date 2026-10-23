-- An anonymous class is a function where Java means one, and an object otherwise.

local Anonymous = require("src/anonymous")

local sorted = Anonymous.longestFirst({ "ab", "abcd", "abc" })
assert(sorted[1] == "abcd" and sorted[3] == "ab", "an anonymous comparator is a function")

local counts = {}
Anonymous.repeated(3, counts)
assert(#counts == 3 and counts[3] == 2, "an anonymous Runnable")

local echo = Anonymous.echo("> ")
assert(echo:heard("hi") == "> hi (1)" and echo:heard("yo") == "> yo (2)", "an anonymous object keeps its fields")
assert(echo:name() == "echo", "and has more than one method")

local square = Anonymous.unitSquare()
assert(square:area() == 1.0 and square:describe() == "square of 1", "an anonymous subclass replaces a method")

local counted = Anonymous.counted({ "guam", "ab" }, ":")
assert(counted[1] == "guam:4" and counted[2] == "ab:2", "a class inside a method reads the method's locals")
