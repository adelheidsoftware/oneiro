-- The locals Oneiro makes up keep their values apart, hide no global, and give what the source's code gives.

local logged = {}
function log(...)
    logged[#logged + 1] = table.concat({ ... }, " ")
end

local LocalNames = require("src/local_names")

assert(LocalNames.busyGroups({ { 2, 3 }, { 2 }, { 1, 5, 6 } }) == 2, "a count inside a count's lambda")
LocalNames.showBoth({ "a", "b" })
assert(logged[#logged - 1] == "loud 2" and logged[#logged] == "quiet 2", "a list for a parameter, then another")
assert(LocalNames.counts({ "", "abcd", "ab" }) == "3: 1 empty, 1 long", "counts beside a local named count")
assert(LocalNames.describe(5) == "a number" and logged[#logged] == "a number", "a switch's local beside log()")
assert(LocalNames.describe("x") == "something, text", "a switch's local beside type()")
local sections = LocalNames.Sections.new()
sections:keep({ "a", "b" })
assert(#sections.lines == 2 and sections.lines[1] == "A", "a list built for a field")
