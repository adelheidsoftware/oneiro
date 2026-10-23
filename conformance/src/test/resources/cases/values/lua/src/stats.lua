-- A hand-written module whose functions return two values.

local Stats = {}

function Stats.summarise(values)
    local total = 0
    for _, value in ipairs(values) do
        total = total + value
    end
    return #values, total / #values
end

function Stats.extremes(values)
    return math.min(table.unpack(values)), math.max(table.unpack(values))
end

return Stats
