-- src/herbs.lua
-- Herb runs, summed up with streams.

local Herbs = {}

-- The herbs of the harvests that gave at least the minimum, in upper case.
---@param harvests table[]
---@param minimum integer
---@return string[]
function Herbs.good(harvests, minimum)
    local herbs = {}
    for _, harvest in ipairs(harvests) do
        if harvest.count >= minimum then
            herbs[#herbs + 1] = harvest.herb:upper()
        end
    end
    return herbs
end

-- How many harvests gave nothing.
---@param harvests table[]
---@return integer
function Herbs.empty(harvests)
    local count = 0
    for _, harvest in ipairs(harvests) do
        if harvest.count == 0 then
            count = count + 1
        end
    end
    return count
end

-- Whether any harvest was of the herb.
---@param harvests table[]
---@param herb string
---@return boolean
function Herbs.grew(harvests, herb)
    for _, harvest in ipairs(harvests) do
        if harvest.herb == herb then
            return true
        end
    end
    return false
end

-- Whether every harvest gave something.
---@param harvests table[]
---@return boolean
function Herbs.allGave(harvests)
    local gave = true
    for _, harvest in ipairs(harvests) do
        if not (harvest.count > 0) then
            gave = false
            break
        end
    end
    return gave
end

-- The first herb with the prefix, or "none".
---@param herbs string[]
---@param prefix string
---@return string
function Herbs.firstWith(herbs, prefix)
    for _, herb in ipairs(herbs) do
        if herb:sub(1, #prefix) == prefix then
            return herb
        end
    end
    return "none"
end

-- The herbs picked in all.
---@param harvests table[]
---@return integer
function Herbs.total(harvests)
    local sum = 0
    for _, harvest in ipairs(harvests) do
        sum = sum + harvest.count
    end
    return sum
end

-- The biggest harvest's count, or 0 when there were none.
---@param harvests table[]
---@return integer
function Herbs.best(harvests)
    local max
    for _, harvest in ipairs(harvests) do
        local value = harvest.count
        if max == nil or max < value then
            max = value
        end
    end
    return max or 0
end

-- The herbs as one line in lower case, such as "guam, marrentill".
---@param herbs string[]
---@return string
function Herbs.line(herbs)
    local parts = {}
    for _, herb in ipairs(herbs) do
        parts[#parts + 1] = herb:lower()
    end
    return table.concat(parts, ", ")
end

-- Counts down from the number, into the list.
---@param from integer
---@param numbers integer[]
function Herbs.countdown(from, numbers)
    for i = 1, from do
        numbers[#numbers + 1] = from - i + 1
    end
end

-- The harvests from the most to the least picked.
---@param harvests table[]
---@return table[]
function Herbs.ranked(harvests)
    local list = table.move(harvests, 1, #harvests, 1, {})
    table.sort(list, function(a, b)
        return b.count < a.count
    end)
    return list
end

-- The herbs the stock has any of.
---@param stock table<string, integer>
---@return table<string, true>
function Herbs.stocked(stock)
    local herbs = {}
    for herb in pairs(stock) do
        if stock[herb] > 0 then
            herbs[herb] = true
        end
    end
    return herbs
end

-- Sorts the harvests by herb name, last first.
---@param harvests table[]
function Herbs.byName(harvests)
    table.sort(harvests, function(a, b)
        return b.herb < a.herb
    end)
end

-- Sorts the harvests by herb, then by count.
---@param harvests table[]
function Herbs.byHerbThenCount(harvests)
    table.sort(harvests, function(a, b)
        return a.herb < b.herb or (a.herb == b.herb and a.count < b.count)
    end)
end

-- Whether a herb's name is long.
---@param herb string
---@return boolean
local function isLong(herb)
    return #herb > 6
end

-- The herbs with long names.
---@param herbs string[]
---@return string[]
function Herbs.longNames(herbs)
    local list = {}
    for _, item in ipairs(herbs) do
        if isLong(item) then
            list[#list + 1] = item
        end
    end
    return list
end

return Herbs
