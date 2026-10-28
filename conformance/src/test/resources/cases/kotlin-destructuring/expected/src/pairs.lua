-- src/pairs.lua
-- Kotlin's pairs and destructuring: of pairs, data classes and lists, partition, and the positions of a list.

local orderedTable = require("utils/ordered_table")

local Pairs = {}

-- A name and its level, as a pair.
---@param name string
---@param level integer
---@return table
function Pairs.entry(name, level)
    return { first = name, second = level }
end

-- A name and its level, as text.
---@param entry table
---@return string
function Pairs.describe(entry)
    return formatStr("{} {}", entry.first, entry.second)
end

-- The lower and the higher of two levels.
---@param a integer
---@param b integer
---@return table
function Pairs.ordered(a, b)
    return a < b and { first = a, second = b } or { first = b, second = a }
end

-- The level of a pair, read by destructuring.
---@param entry table
---@return integer
function Pairs.level(entry)
    local level = entry.second
    return level
end

-- The difference of two levels, from a pair made in place.
---@param low integer
---@param high integer
---@return integer
function Pairs.difference(low, high)
    local first, second = high, low
    return first - second
end

-- The gap between two levels, whichever is higher.
---@param a integer
---@param b integer
---@return integer
function Pairs.gap(a, b)
    local pair = Pairs.ordered(a, b)
    local low, high = pair.first, pair.second
    return high - low
end

-- A drop as text.
---@param drop table
---@return string
function Pairs.label(drop)
    local item, count = drop.item, drop.count
    return formatStr("{} x {}", count, item)
end

-- The first two names of a list.
---@param names string[]
---@return string
function Pairs.firstTwo(names)
    local first, second = names[1], names[2]
    return formatStr("{}, {}", first, second)
end

-- How many stacks and single drops there were, as text.
---@param drops table[]
---@return string
function Pairs.split(drops)
    local stacks, singles = {}, {}
    for _, it in ipairs(drops) do
        if it.count > 1 then
            stacks[#stacks + 1] = it
        else
            singles[#singles + 1] = it
        end
    end
    return formatStr("{} stacks, {} singles", #stacks, #singles)
end

-- Each name with its level, as lines.
---@param entries table[]
---@return string
function Pairs.lines(entries)
    local text = ""
    for _, pair in ipairs(entries) do
        local name, level = pair.first, pair.second
        text = text .. formatStr("{}: {}\n", name, level)
    end
    return text
end

-- The total count of the drops of the item.
---@param drops table[]
---@param wanted string
---@return integer
function Pairs.countOf(drops, wanted)
    local total = 0
    for _, drop in ipairs(drops) do
        local item, count = drop.item, drop.count
        if item == wanted then
            total = total + count
        end
    end
    return total
end

-- The names, numbered from 1.
---@param names string[]
---@return string
function Pairs.numbered(names)
    local text = ""
    for index, name in ipairs(names) do
        text = text .. formatStr("{}. {} ", index, name)
    end
    return text
end

-- The names before the position.
---@param names string[]
---@param position integer
---@return string[]
function Pairs.before(names, position)
    local kept = {}
    for i, name in ipairs(names) do
        local index = i - 1
        if index < position then
            kept[#kept + 1] = name
        end
    end
    return kept
end

-- Each name with the level at its position.
---@param names string[]
---@param levels integer[]
---@return string
function Pairs.withLevels(names, levels)
    local text = ""
    for index, name in ipairs(names) do
        text = text .. formatStr("{} {};", name, levels[index])
    end
    return text
end

-- The names, each after its position counted from 0.
---@param names string[]
---@return string[]
function Pairs.positions(names)
    local list = {}
    for i, name in ipairs(names) do
        local index = i - 1
        list[#list + 1] = formatStr("{}={}", index, name)
    end
    return list
end

-- Each drop's count by its item.
---@param drops table[]
---@return table<string, integer>
function Pairs.counts(drops)
    local map = orderedTable()
    for _, it in ipairs(drops) do
        map[it.item] = it.count
    end
    return map
end

-- The levels by name.
---@param entries table[]
---@return table<string, integer>
function Pairs.levels(entries)
    local map = orderedTable()
    for _, pair in ipairs(entries) do
        map[pair.first] = pair.second
    end
    return map
end

return Pairs
