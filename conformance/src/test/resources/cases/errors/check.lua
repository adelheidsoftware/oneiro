local logged = {}
function log(...)
    logged[#logged + 1] = table.concat({ ... }, " ")
end

local Parsing = require("src/parsing")

Parsing.run(function() end)
assert(Parsing.failureCount() == 0 and #logged == 0, "a task that succeeds is not counted")
Parsing.run(function()
    error("broken")
end)
assert(Parsing.failureCount() == 1, "a failing task is counted")
assert(logged[1]:find("The task failed:", 1, true) == 1 and logged[1]:find("broken", 1, true), "and logged")

assert(Parsing.number("abc", -1) == 3, "the value when nothing fails")
assert(Parsing.number("", -1) == -1, "the fallback when it does")

assert(Parsing.outcome("ab") == "number 2", "the try block's value")
local failed = Parsing.outcome("!x")
assert(failed:find("^error ") and failed:find("not a measure: !x", 1, true), "the catch block's value: " .. failed)

Parsing.strict("abc")
Parsing.strict("")
local ok, raised = pcall(Parsing.strict, "!y")
assert(not ok and raised:find("not a measure: !y", 1, true), "an error thrown again keeps its message")

Parsing.check("")
local checked, thrown = pcall(Parsing.check, "gear")
assert(not checked and thrown:find("broken: gear", 1, true), "an exception kept in a local is thrown later")
assert(Parsing.message("lost") == "lost", "a kept exception's message")
