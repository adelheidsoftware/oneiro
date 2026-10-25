-- src/core/levels.lua
-- The plugin's own levels module, which the case's code calls through the bindings its annotations give.

local Levels = {}

Levels.PERFECT = 0

---@param penalty integer
---@return integer level
function Levels.fromPenalty(penalty)
    return penalty // 2
end

return Levels
