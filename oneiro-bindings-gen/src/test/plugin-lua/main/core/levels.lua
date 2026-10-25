-- src/core/levels.lua
-- The levels an arrangement can score, from 0 (Perfect) up. A module that declares no class for its table.

local Levels = {}

Levels.PERFECT = 0

-- Half the penalty, rounded down.
---@param penalty integer the sum of the slots' distances
---@return integer level
function Levels.fromPenalty(penalty)
    return penalty // 2
end

return Levels
