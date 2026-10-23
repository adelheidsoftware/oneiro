-- The collections' own methods give Java's results.

local Tally = require("src/tally")

local counts = Tally.counts({ "ash", "oak", "ash" })
assert(counts.ash == 2 and counts.oak == 1, "merge with Integer::sum counts")

local groups = Tally.byInitial({ "ash", "oak", "apple" })
assert(#groups.a == 2 and groups.a[2] == "apple" and #groups.o == 1, "computeIfAbsent groups")

local words = { "ox", "ash", "an", "oak" }
local seen = { [""] = true, ash = true }
Tally.prune(words, seen)
assert(#words == 2 and words[1] == "ash" and words[2] == "oak", "removeIf keeps the order of what stays")
assert(seen[""] == nil and seen.ash, "removeIf on a set")

assert(Tally.enabled({}, "x") == true, "an unset feature is on")
assert(Tally.enabled({ x = false }, "x") == false, "a stored false is kept, which or would lose")
assert(Tally.enabled({ x = true }, "x") == true, "a stored true")

local fields = Tally.fields("a,b,,c,,")
assert(#fields == 4 and fields[3] == "" and fields[4] == "c", "split drops only the trailing empty parts")
assert(#Tally.fields("") == 1, "an empty line is one empty field, as in Java")
assert(#Tally.fields(",") == 0, "a line of only separators has no fields, as in Java")

local jobs = { "first" }
assert(Tally.next(jobs) == "urgent", "addFirst puts a job in front")
assert(#jobs == 2 and jobs[1] == "first" and jobs[2] == "later", "addLast puts it at the back")

assert(Tally.describe({ ash = 2 }) == "counts: {ash=2}", "a map as Java writes it")

local firstSeen = { ash = 1 }
Tally.remember(firstSeen, "ash", 5)
Tally.remember(firstSeen, "oak", 7)
assert(firstSeen.ash == 1 and firstSeen.oak == 7, "putIfAbsent keeps the first value")

assert(Tally.reversed("oak") == "[kao]", "reverse and insert edit the builder in place")
