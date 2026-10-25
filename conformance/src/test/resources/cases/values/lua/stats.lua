-- src/stats.lua
-- The plugin's own statistics module, whose functions return two values, as its annotations name them.

local Stats = {}

---@param values integer[]
---@return integer count
---@return number mean
function Stats.summarise(values)
    local total = 0
    for _, value in ipairs(values) do
        total = total + value
    end
    return #values, total / #values
end

---@param values integer[]
---@return integer least
---@return integer most
function Stats.extremes(values)
    return math.min(table.unpack(values)), math.max(table.unpack(values))
end

return Stats
