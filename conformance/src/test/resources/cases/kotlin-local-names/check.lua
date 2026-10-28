-- The locals Oneiro makes up keep their values apart, and give what Kotlin's code gives.

local logged = {}
function log(...)
    logged[#logged + 1] = table.concat({ ... }, " ")
end

local LocalNames = require("src/local_names")

assert(LocalNames.busyGroups({ { 2, 3 }, { 2 }, { 1, 5, 6 } }) == 2, "a count inside a count's lambda")
LocalNames.showBoth({ "a", "b" })
assert(logged[#logged - 1] == "loud 2" and logged[#logged] == "quiet 2", "a list for a parameter, then another")
assert(LocalNames.counts({ "", "abcd", "ab" }) == "3: 1 empty, 1 long", "counts beside a local named count")
LocalNames.rows({ "a", "b", "c", "d" })
assert(logged[#logged] == "rows 2", "rows of a list a call gives")
local sections = LocalNames.Sections.new()
sections:keep("skills", 2)
assert(#sections.lines == 2 and sections.lines[1] == "skills", "a list built for a property")
sections:first("herbs")
assert(sections.rows[1][1] == "herbs", "a list built for an element of a property")
