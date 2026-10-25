-- The widgets library, a fixture of annotated library code: a module that gathers the others into its fields.

---@class widgets
local widgets = {
    Counter = require("widgets/Counter"),
    LoudCounter = require("widgets/LoudCounter"),
    text = require "widgets/text",
}

return widgets
