-- The scope functions' code runs where they are called, on the receiver, and gives what Kotlin's give.

local logged = {}
function log(...)
    logged[#logged + 1] = table.concat({ ... }, " ")
end

-- The client's text components, as far as the panels use them
ui = { Text = {} }
ui.Text.__index = ui.Text
function ui.Text.new(parent)
    return setmetatable({ parent = parent, hidden = false }, ui.Text)
end
function ui.Text:SetPos(x, y)
    self.x, self.y = x, y
end

local Panels = require("src/panels")

-- Before the first tally, there is none to work on
Panels.addToKept(5)
Panels.reportKept()
assert(#logged == 0 and Panels.logKept() == nil and #logged == 0, "?.apply and ?.also on nil run nothing")
Panels.hideIfAny(nil)
Panels.cornerIfAny(nil)

local parent = {}
local label = Panels.label(parent, "Guam")
assert(label.parent == parent and label.content == "Guam" and label.x == 10 and label.y == 20, "apply returned")
local shown = Panels.shown(parent, "Ranarr")
assert(shown.content == "Ranarr" and shown.x == 10 and not shown.hidden, "apply into a local")
Panels.hide(shown)
assert(shown.hidden and shown.x == 0, "apply on a parameter")

assert(Panels.shout("guam") == "GUAM", "let into a local")
assert(Panels.trimmedLength("  guam ") == 4 and Panels.trimmedLength(nil) == 0, "?.let ?: 0")

local tally = Panels.tallied(5)
assert(tally.total == 5, "also on a new object")
assert(Panels.logged(tally) == tally and logged[#logged] == "total 5", "also on a parameter")
assert(Panels.total(tally, 2, 3) == 10, "run with the receiver's members")
assert(Panels.fresh(4) == 5, "run on a new object, into a local")
assert(Panels.describe(tally) == "total 10", "with, and this")

Panels.logLong({ "guam", "ash", "ranarr" })
assert(logged[#logged - 1] == "GUAM" and logged[#logged] == "RANARR", "let as a statement")
assert(Panels.nameLength("guam") == "length 4", "let inside an expression")
Panels.demo()
assert(logged[#logged] == "total 5", "apply on a new object, whose value goes nowhere")

assert(Panels.Panel.new(parent):text() == "Ready", "apply in a class's property")
local first = Panels.sharedTally()
assert(first.total == 10 and Panels.sharedTally() == first, "apply assigned to a variable, and an object's private var")
Panels.addToKept(5)
assert(first.total == 15 and logged[#logged] == "kept 15", "?.apply as a statement, on a call")
local count = #logged
assert(Panels.logKept() == first and #logged == count + 1 and logged[#logged] == "kept 15", "?.apply as a local's value")
Panels.reportKept()
assert(logged[#logged] == "total 15", "?.also as a statement")
local corner = Panels.label(parent, "Corner")
Panels.hideIfAny(corner)
Panels.cornerIfAny(corner)
assert(corner.hidden and corner.x == 0 and corner.y == 0, "?.apply and ?.run on a parameter")

local pair = Panels.pair("guam", "ash")
assert(#pair == 2 and pair[1] == "guam" and pair[2] == "ash", "apply with the library's members on its receiver")
assert(Panels.count({ "guam", "ash" }) == 2, "with, and a library property of its receiver")
