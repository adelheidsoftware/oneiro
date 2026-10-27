-- src/drops.lua
-- Drops summed up with the collection functions Kotlin has beyond Java's streams.

-- A table whose pairs walks its keys in the order they were first added, as Kotlin's maps and sets do, but for its hash
-- maps and hash sets. It holds its entries itself; its metatable keeps their order.
local function orderedTable()
    local keys = {}
    local added = {}
    return setmetatable({}, {
        __newindex = function(t, key, value)
            if value == nil then
                return
            end
            if added[key] then
                -- A key removed and added again goes to the end, as in a LinkedHashMap
                for i = #keys, 1, -1 do
                    if keys[i] == key then
                        table.remove(keys, i)
                        break
                    end
                end
            end
            added[key] = true
            keys[#keys + 1] = key
            rawset(t, key, value)
        end,
        __pairs = function(t)
            local i = 0
            return function()
                repeat
                    i = i + 1
                    local key = keys[i]
                    -- A key removed since it was added is passed over
                    if key ~= nil and rawget(t, key) ~= nil then
                        return key, rawget(t, key)
                    end
                until key == nil
            end
        end,
    })
end

local Drops = {}

-- The items that were not coins.
---@param drops table[]
---@return string[]
function Drops.items(drops)
    local list = {}
    for _, it in ipairs(drops) do
        if it.item ~= "coins" then
            list[#list + 1] = it.item
        end
    end
    return list
end

-- What each wanted drop is worth, leaving out the rest.
---@param drops table[]
---@param wanted table<string, true>
---@return number[]
function Drops.worths(drops, wanted)
    local list = {}
    for _, drop in ipairs(drops) do
        local value = wanted[drop.item] ~= nil and drop.count * drop.price or nil
        if value ~= nil then
            list[#list + 1] = value
        end
    end
    return list
end

-- What the drops are worth together.
---@param drops table[]
---@return number
function Drops.worth(drops)
    local sum = 0.0
    for _, it in ipairs(drops) do
        sum = sum + it.count * it.price
    end
    return sum
end

-- The drop worth the most, or null for none.
---@param drops table[]
---@return table?
function Drops.best(drops)
    local max
    for _, it in ipairs(drops) do
        if max == nil or max.count * max.price < it.count * it.price then
            max = it
        end
    end
    return max
end

-- The smallest stack.
---@param drops table[]
---@return integer
function Drops.smallest(drops)
    local min
    for _, it in ipairs(drops) do
        local value = it.count
        if min == nil or value < min then
            min = value
        end
    end
    return min
end

-- The biggest stack by a comparator.
---@param drops table[]
---@return table?
function Drops.biggest(drops)
    local max
    for _, item in ipairs(drops) do
        if max == nil or max.count < item.count then
            max = item
        end
    end
    return max
end

-- The first drop of the item, or null.
---@param drops table[]
---@param item string
---@return table?
function Drops.find(drops, item)
    for _, it in ipairs(drops) do
        if it.item == item then
            return it
        end
    end
    return nil
end

-- The last drop worth more than the price.
---@param drops table[]
---@param price number
---@return table?
function Drops.lastWorth(drops, price)
    local last
    for _, it in ipairs(drops) do
        if it.price > price then
            last = it
        end
    end
    return last
end

-- Whether no drop was of the item.
---@param drops table[]
---@param item string
---@return boolean
function Drops.missed(drops, item)
    for _, it in ipairs(drops) do
        if it.item == item then
            return false
        end
    end
    return true
end

-- How many drops were stacks.
---@param drops table[]
---@return integer
function Drops.stacks(drops)
    local count = 0
    for _, it in ipairs(drops) do
        if it.count > 1 then
            count = count + 1
        end
    end
    return count
end

-- The items as one line, such as "[bones, ashes]".
---@param drops table[]
---@return string
function Drops.line(drops)
    local parts = {}
    for _, it in ipairs(drops) do
        parts[#parts + 1] = it.item
    end
    return "[" .. table.concat(parts, ", ") .. "]"
end

-- The counts as one line, such as "3 + 1".
---@param drops table[]
---@return string
function Drops.counts(drops)
    local parts = {}
    for _, it in ipairs(drops) do
        parts[#parts + 1] = tostring(it.count)
    end
    return table.concat(parts, " + ")
end

-- The items that dropped, each once, in the order they first dropped.
---@param drops table[]
---@return table<string, true>
function Drops.kinds(drops)
    local set = orderedTable()
    for _, it in ipairs(drops) do
        set[it.item] = true
    end
    return set
end

-- The items by name, last first.
---@param drops table[]
---@return string[]
function Drops.names(drops)
    local list = {}
    for _, it in ipairs(drops) do
        list[#list + 1] = it.item
    end
    table.sort(list, function(a, b)
        return b < a
    end)
    return list
end

-- The drops from the most to the least valuable, ties by name.
---@param drops table[]
---@return table[]
function Drops.ranked(drops)
    local list = table.move(drops, 1, #drops, 1, {})
    table.sort(list, function(a, b)
        return b.count * b.price < a.count * a.price or (a.count * a.price == b.count * b.price and a.item < b.item)
    end)
    return list
end

-- The stacks, by item.
---@param drops table[]
---@return table[]
function Drops.stacksByItem(drops)
    local list = {}
    for _, it in ipairs(drops) do
        if it.count > 1 then
            list[#list + 1] = it
        end
    end
    table.sort(list, function(a, b)
        return a.item < b.item
    end)
    return list
end

-- Sorts the drops by count, the biggest stack first.
---@param drops table[]
function Drops.sortByCount(drops)
    table.sort(drops, function(a, b)
        return b.count < a.count
    end)
end

-- The squares of the numbers up to the limit.
---@param limit integer
---@return integer[]
function Drops.squares(limit)
    local list = {}
    for it = 1, limit do
        list[#list + 1] = it * it
    end
    return list
end

-- The numbers from the start down to 1 that are above the floor.
---@param start integer
---@param floor integer
---@return integer[]
function Drops.above(start, floor)
    local list = {}
    for it = start, 1, -1 do
        if it > floor then
            list[#list + 1] = it
        end
    end
    return list
end

-- How many the stock holds in all.
---@param stock table<string, integer>
---@return integer
function Drops.stocked(stock)
    local sum = 0
    for _, value in pairs(stock) do
        sum = sum + value
    end
    return sum
end

-- The stocked items whose names are longer than the limit.
---@param stock table<string, integer>
---@param limit integer
---@return string[]
function Drops.longNames(stock, limit)
    local list = {}
    for it in pairs(stock) do
        if #it > limit then
            list[#list + 1] = it
        end
    end
    return list
end

-- What the drops worth more than the limit are worth.
---@param drops table[]
---@param limit number
---@return number[]
function Drops.valuable(drops, limit)
    local list = {}
    for _, it in ipairs(drops) do
        local value = it.count * it.price
        if value > limit then
            list[#list + 1] = value
        end
    end
    return list
end

-- Logs each drop worth more than the price.
---@param drops table[]
---@param price number
function Drops.logWorth(drops, price)
    for _, it in ipairs(drops) do
        if it.price > price then
            log("worth it:", it.item)
        end
    end
end

-- The first item worth more than the price, or "nothing".
---@param drops table[]
---@param price number
---@return string
function Drops.firstWorth(drops, price)
    local found
    for _, it in ipairs(drops) do
        if it.price > price then
            found = it
            break
        end
    end
    if found == nil then
        return "nothing"
    end
    return found.item
end

-- The biggest stack of the item, or 0, kept in a variable first.
---@param drops table[]
---@param item string
---@return integer
function Drops.mostOf(drops, item)
    local most = 0
    local max
    for _, it in ipairs(drops) do
        if it.item == item then
            local value = it.count
            if max == nil or max < value then
                max = value
            end
        end
    end
    most = max or 0
    return most
end

-- How many drops worthMore has looked at.
Drops.looks = 0

-- Whether the drop is worth more than the price, counted in looks.
---@param drop table
---@param price number
---@return boolean
function Drops.worthMore(drop, price)
    Drops.looks = Drops.looks + 1
    return drop.price > price
end

-- Logs the items worth more than the price on one line, looking at each drop once.
---@param drops table[]
---@param price number
function Drops.logItems(drops, price)
    local list = {}
    for _, it in ipairs(drops) do
        if Drops.worthMore(it, price) then
            list[#list + 1] = it.item
        end
    end
    log(table.concat(list, ","))
end

return Drops
