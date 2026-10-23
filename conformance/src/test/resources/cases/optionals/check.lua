-- An Optional is its value or nil.

local Targets = require("src/targets")

local names = { "Ash", "Birch", "Cedar" }
assert(Targets.firstWith(names, "B") == "Birch" and Targets.firstWith(names, "Z") == nil, "a value or nil")
assert(Targets.shown(names) == "Ash" and Targets.shown({}) == "nobody", "orElse")
assert(Targets.has(names, "C") and not Targets.has(names, "D"), "isPresent")
assert(Targets.length(names) == 5 and Targets.length({}) == 0, "isEmpty and get")

local greetings = {}
Targets.greet(names, greetings)
assert(#greetings == 2 and greetings[1] == "hi Cedar" and greetings[2] == "nobody", "ifPresent and ifPresentOrElse")
Targets.greet({ "Dogwood" }, greetings)
assert(#greetings == 3 and greetings[3] == "hey Dogwood", "ifPresentOrElse with a value")

assert(Targets.required({ "Elm" }) == "Elm", "orElseThrow with a value")
assert(not pcall(Targets.required, names), "orElseThrow raises without one")
assert(Targets.enabled(nil) and Targets.enabled(true) and not Targets.enabled(false), "orElse keeps a false")
