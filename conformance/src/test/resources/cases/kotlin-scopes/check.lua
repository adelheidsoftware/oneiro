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
