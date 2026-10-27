-- src/levels.lua
-- Levels and experience, as functions and properties outside any class.

local Levels = {}

-- The highest level a skill reaches.
Levels.MAX_LEVEL = 99

-- The lowest level.
local FIRST = 1

-- The level as text, without a star.
---@param level integer
---@return string
local function describe(level)
    return formatStr("level {}", level)
end

-- The level, kept between the first and the highest.
---@param level integer
---@return integer
function Levels.clampLevel(level)
    return math.min(math.max(level, FIRST), Levels.MAX_LEVEL)
end

-- Whether the level is the highest.
---@param level integer
---@return boolean
function Levels.isMaxed(level)
    return level >= Levels.MAX_LEVEL
end

-- The level as text, with a star when it is the highest.
---@param self integer
---@return string
function Levels.levelText(self)
    return Levels.isMaxed(self) and formatStr("{}*", self) or describe(self)
end

return Levels
