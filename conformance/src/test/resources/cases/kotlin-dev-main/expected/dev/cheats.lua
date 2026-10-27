-- dev/cheats.lua
-- Dev code that calls the main source set's top-level functions.

local Levels = require("src/levels")

local Cheats = {}

-- A level past the highest, clamped.
---@return integer
function Cheats.clamped()
    return Levels.clamp(120)
end

-- A level as text.
---@param level integer
---@return string
function Cheats.text(level)
    return Levels.levelText(level)
end

return Cheats
