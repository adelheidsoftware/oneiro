-- src/walker.lua
-- Locals named like the file's private members, which share one namespace with them in Lua.

local Walker = {}

-- Declared ahead: used above their definitions below.
local step

local total = 10

-- The steps from n down to 1.
---@param n integer
---@return integer
function Walker.countDown(n)
    return step(n)
end

function step(n)
    local step_ = n - 1
    if step_ <= 0 then
        return 0
    end
    return 1 + step(step_)
end

-- The amount added to the running total, which this function reads by its qualified name.
---@param amount integer
---@return integer
function Walker.plusTotal(amount)
    local total_ = amount
    return total_ + total
end

-- Twice the steps; a parameter named like a private function this function does not use keeps its name.
---@param step integer
---@return integer
function Walker.doubled(step)
    return step * 2
end

return Walker
