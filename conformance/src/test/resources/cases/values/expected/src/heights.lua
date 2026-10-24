-- src/heights.lua
-- Several values returned from Lua functions, and returned to Lua.

local Stats = require("src/stats")

local Heights = {}

-- The height of the ground, or the fallback where the world has none.
---@param x integer
---@param z integer
---@param fallback integer the height to use instead
---@return integer
function Heights.groundOr(x, z, fallback)
    local height, available = World.GetGroundHeight(0, x, z)
    if not available then
        return fallback
    end
    return height
end

function Heights.lowest(x, z)
    local height = World.GetGroundHeight(0, x, z)
    local eastHeight = World.GetGroundHeight(0, x + 512, z)
    return math.min(height, eastHeight)
end

-- The span of the values.
---@param values integer[]
---@return integer low
---@return integer high
function Heights.span(values)
    local low = values[1]
    local high = low
    for _, value in ipairs(values) do
        low = math.min(low, value)
        high = math.max(high, value)
    end
    return low, high
end

function Heights.width(values)
    local low, high = Heights.span(values)
    return high - low
end

function Heights.spanOfAll(values)
    return Heights.span(values)
end

function Heights.checkedSpan(values)
    local low, high = Heights.span(values)
    if low < 0 then
        error("negative values")
    end
    return low, high
end

function Heights.report(values)
    local count, mean = Stats.summarise(values)
    return formatStr("{} values, mean {}", count, mean)
end

function Heights.range(values)
    local least, most = Stats.extremes(values)
    return most - least
end

return Heights
