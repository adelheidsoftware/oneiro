-- src/stock.lua
-- Chains over a map's entries, and loops that read an entry's key and value.

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

local Stock = {}

-- The items there are more of than the least.
---@param stock table<string, integer>
---@param least integer
---@return string[]
function Stock.plenty(stock, least)
    local list = {}
    for item, count in pairs(stock) do
        if count > least then
            list[#list + 1] = item
        end
    end
    return list
end

-- The items there are any of, in a map.
---@param stock table<string, integer>
---@return table<string, integer>
function Stock.inStock(stock)
    local map = orderedTable()
    for key, value in pairs(stock) do
        if value > 0 then
            map[key] = value
        end
    end
    return map
end

-- How many items there are more than one of.
---@param stock table<string, integer>
---@return integer
function Stock.stacks(stock)
    local count = 0
    for _, value in pairs(stock) do
        if value > 1 then
            count = count + 1
        end
    end
    return count
end

-- The item there is most of, or null for none.
---@param stock table<string, integer>
---@return string?
function Stock.mostCommon(stock)
    local best
    local most
    for key, value in pairs(stock) do
        if most == nil or most < value then
            most = value
            best = key
        end
    end
    return best
end

-- How many there are of the item there is least of, or 0 for none.
---@param stock table<string, integer>
---@return integer
function Stock.fewest(stock)
    local best
    local least
    for _, count in pairs(stock) do
        if least == nil or count < least then
            least = count
            best = count
        end
    end
    local fewest = best or 0
    return fewest
end

-- The first item there are as many of as wanted, or null for none.
---@param stock table<string, integer>
---@param wanted integer
---@return string?
function Stock.firstWith(stock, wanted)
    for key, value in pairs(stock) do
        if value == wanted then
            return key
        end
    end
    return nil
end

-- What each item's stack is worth.
---@param stock table<string, integer>
---@param price integer
---@return table<string, integer>
function Stock.worths(stock, price)
    local map = orderedTable()
    for key, count in pairs(stock) do
        map[key] = count * price
    end
    return map
end

-- The stock with its items' names in capitals.
---@param stock table<string, integer>
---@return table<string, integer>
function Stock.shouted(stock)
    local map = orderedTable()
    for key, value in pairs(stock) do
        map[key:upper()] = value
    end
    return map
end

-- The items with long names, of which there are more than two.
---@param stock table<string, integer>
---@return table<string, integer>
function Stock.longNames(stock)
    local map = orderedTable()
    for key, value in pairs(stock) do
        if #key > 4 and value > 2 then
            map[key] = value
        end
    end
    return map
end

-- The stock as lines, "item: count".
---@param stock table<string, integer>
---@return string
function Stock.lines(stock)
    local parts = {}
    for key, value in pairs(stock) do
        parts[#parts + 1] = formatStr("{}: {}", key, value)
    end
    return table.concat(parts, "\n")
end

-- How many there are of all the items together.
---@param stock table<string, integer>
---@return integer
function Stock.total(stock)
    local total = 0
    for _, value in pairs(stock) do
        total = total + value
    end
    return total
end

-- The items' names.
---@param stock table<string, integer>
---@return string[]
function Stock.items(stock)
    local items = {}
    for key in pairs(stock) do
        items[#items + 1] = key
    end
    return items
end

return Stock
