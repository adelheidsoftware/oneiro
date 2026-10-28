-- src/folds.lua
-- Kotlin's collection functions that fold, flatten and build lists, and run blocks that give a value.

local reversed = require("utils/reversed").reversed

local Folds = {}

-- The drops' counts, added up from the start.
---@param drops table[]
---@param start integer
---@return integer
function Folds.total(drops, start)
    local sum = start
    for _, drop in ipairs(drops) do
        sum = sum + drop.count
    end
    return sum
end

-- The biggest count, or null for none.
---@param counts integer[]
---@return integer?
function Folds.biggest(counts)
    local biggest
    for _, count in ipairs(counts) do
        if biggest == nil then
            biggest = count
        else
            biggest = count > biggest and count or biggest
        end
    end
    return biggest
end

-- The average count.
---@param drops table[]
---@return number
function Folds.averageCount(drops)
    local sum, count = 0, 0
    for _, it in ipairs(drops) do
        sum = sum + it.count
        count = count + 1
    end
    return sum / count
end

-- The one drop of the item, or null for none or more than one.
---@param drops table[]
---@param item string
---@return table?
function Folds.only(drops, item)
    local single
    for _, it in ipairs(drops) do
        if it.item == item then
            if single ~= nil then
                single = nil
                break
            end
            single = it
        end
    end
    return single
end

-- Every item of every haul.
---@param hauls string[][]
---@return string[]
function Folds.everything(hauls)
    local list = {}
    for _, items in ipairs(hauls) do
        for _, item in ipairs(items) do
            list[#list + 1] = item
        end
    end
    return list
end

-- The names among values that may be anything.
---@param values any[]
---@return string[]
function Folds.names(values)
    local list = {}
    for _, item in ipairs(values) do
        if type(item) == "string" then
            list[#list + 1] = item
        end
    end
    return list
end

-- The items, last first.
---@param items string[]
---@return string[]
function Folds.backwards(items)
    return reversed(items)
end

-- The items worth keeping: the rare one first, then every item that is not coins.
---@param items string[]
---@param rare string?
---@return string[]
function Folds.keep(items, rare)
    local list = {}
    if rare ~= nil then
        list[#list + 1] = rare
    end
    for _, item in ipairs(items) do
        if item ~= "coins" then
            list[#list + 1] = item
        end
    end
    return list
end

-- The label for a count, worked out in a block of its own.
---@param count integer
---@return string
function Folds.label(count)
    local label
    do
        local many = count > 1
        label = many and "stacks" or "one"
    end
    return label
end

-- Twice the count, from a block of one value.
---@param count integer
---@return integer
function Folds.doubled(count)
    local doubled = count * 2
    return doubled
end

-- The count after the next drop, as text.
---@param count integer
---@return string
function Folds.nextCount(count)
    local value
    do
        local following = count + 1
        value = following
    end
    return formatStr("Next: {}", value)
end

return Folds
