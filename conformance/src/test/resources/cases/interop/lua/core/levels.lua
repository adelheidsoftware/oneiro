-- src/core/levels.lua
-- The plugin's own levels module, which the case's code calls through the bindings its annotations give.

local Signal = require("src/core/signal")

local Levels = {}

Levels.PERFECT = 0

---Fires with each level fromPenalty gives; its listeners give nothing back.
---@type Signal<integer, nil>
Levels.computed = Signal.new()

---@param penalty integer
---@return integer level
function Levels.fromPenalty(penalty)
    local level = penalty // 2
    Levels.computed:fire(level)
    return level
end

return Levels
