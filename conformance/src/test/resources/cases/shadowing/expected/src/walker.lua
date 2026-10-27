-- src/walker.lua
-- Locals named like the file's private members, which share one namespace with them in Lua.

-- The number of entries in a map or set, as Java's size(); a Lua table keeps no count.
local function tableLength(t)
    local n = 0
    for _ in pairs(t) do
        n = n + 1
    end
    return n
end

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

-- The amount added to the running total, which this method reads by its qualified name.
---@param amount integer
---@return integer
function Walker.plusTotal(amount)
    local total_ = amount
    return total_ + total
end

-- Twice the steps; a parameter named like a private method this method does not use keeps its name.
---@param step integer
---@return integer
function Walker.doubled(step)
    return step * 2
end

local function sum(a, b)
    return a + b
end

-- Twice each value, added up, and the sum of one and two.
---@param values integer[]
---@return integer
function Walker.doubledSum(values)
    local doubled = 0
    for _, value in ipairs(values) do
        doubled = doubled + value * 2
    end
    return doubled + sum(1, 2)
end

-- For each factor, the values times it, then the values as they are.
---@param list integer[]
---@param factors integer[]
---@return integer[][]
function Walker.scaled(list, factors)
    local out = {}
    for _, factor in ipairs(factors) do
        local times = {}
        for _, value in ipairs(list) do
            times[#times + 1] = value * factor
        end
        out[#out + 1] = times
        out[#out + 1] = list
    end
    return out
end

-- How many values are below the number of keys.
---@param values integer[]
---@param keys table<string, integer>
---@return integer
function Walker.belowKeys(values, keys)
    local count = 0
    for _, value in ipairs(values) do
        if value < tableLength(keys) then
            count = count + 1
        end
    end
    return count
end

return Walker
