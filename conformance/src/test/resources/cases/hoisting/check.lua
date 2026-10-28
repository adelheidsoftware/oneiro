-- Values read into a local before the statement that uses them give what Java's and Kotlin's do.

local logged = {}
function log(...)
    logged[#logged + 1] = table.concat({ ... }, " ")
end

local Hoisting = require("src/hoisting")

Hoisting.report(Hoisting.Mood.ANGRY)
assert(logged[#logged] == "The monster is angry", "a switch inside a call")
assert(Hoisting.warn(Hoisting.Mood.ANGRY, false) == true and Hoisting.warn(Hoisting.Mood.CALM, false) == false,
    "a conditional of booleans inside a call")
Hoisting.empties({ "", "a", "" })
assert(logged[#logged] == "2 empty", "a stream inside a call")
Hoisting.sizes({ "abcdefg", "ab", "abc" })
assert(logged[#logged] == "1 long, 2 short", "two streams in one statement")
assert(Hoisting.dropped({ "bones", "ashes" }, "ashes") and not Hoisting.dropped({ "bones" }, "ashes")
    and not Hoisting.dropped({ "" }, ""), "a stream on the right of &&")
assert(Hoisting.changed({}) == "nothing" and Hoisting.changed({ "a", "b" }) == "A, B",
    "a conditional with a stream in a branch")
local lines = Hoisting.ledger({ "a", "b" }, "Ledger")
assert(#lines == 3 and lines[1] == "Ledger" and lines[3] == "- b", "a call's list added to another")
assert(Hoisting.afterMiddle("abcd") == "d", "a char at a position worked out once")
local counts = { 0, 0, 0 }
Hoisting.bump(counts, "abcd")
assert(counts[3] == 1, "a count at a position worked out once")
