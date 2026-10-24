-- src/ledger.lua
-- Drops in the order they came, and in sorted order.

-- The set as Java writes it, [a, b], in Lua's order, which is no more fixed than a HashSet's.
local function setText(set)
    local parts = {}
    for value in pairs(set) do
        parts[#parts + 1] = tostring(value)
    end
    return "[" .. table.concat(parts, ", ") .. "]"
end

-- The map as Java writes it, {a=1, b=2}, in Lua's order, which is no more fixed than a HashMap's.
local function mapText(map)
    local parts = {}
    for key, value in pairs(map) do
        parts[#parts + 1] = tostring(key) .. "=" .. tostring(value)
    end
    return "{" .. table.concat(parts, ", ") .. "}"
end

-- A table whose pairs walks its keys in the order they were first added, as Java's LinkedHashMap and LinkedHashSet do.
-- It holds its entries itself; its metatable keeps their order.
local function orderedTable()
    local keys = {}
    local added = {}
    return setmetatable({}, {
        __newindex = function(t, key, value)
            if value == nil then
                return
            end
            if added[key] then
                -- A key removed and added again goes to the end, as in Java
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

-- The table's keys as a list, in the order pairs gives them, as Java copies a set or a map's keySet() into a list.
local function keysOf(t)
    local list = {}
    for key in pairs(t) do
        list[#list + 1] = key
    end
    return list
end

-- Adds each element of the list to the set, and gives the set.
local function addElements(set, list)
    for _, value in ipairs(list) do
        set[value] = true
    end
    return set
end

-- Adds each key of the table to the set, and gives the set.
local function addKeys(set, t)
    for key in pairs(t) do
        set[key] = true
    end
    return set
end

-- Puts each entry of the source into the map, and gives the map.
local function copyEntries(map, source)
    for key, value in pairs(source) do
        map[key] = value
    end
    return map
end

-- The first key pairs gives, as a TreeMap's firstKey() or a TreeSet's first(); nil for an empty table, where Java
-- throws.
local function firstKey(t)
    for key in pairs(t) do
        return key
    end
    return nil
end

-- The last key pairs gives, as a TreeMap's lastKey() or a TreeSet's last(); nil for an empty table, where Java throws.
local function lastKey(t)
    local last = nil
    for key in pairs(t) do
        last = key
    end
    return last
end

-- A table whose pairs walks its keys in sorted order, as Java's TreeMap and TreeSet do; comesBefore orders them, and
-- Lua's < when it is nil.
local function sortedTable(comesBefore)
    return setmetatable({}, {
        __pairs = function(t)
            local keys = {}
            for key in next, t do
                keys[#keys + 1] = key
            end
            table.sort(keys, comesBefore)
            local i = 0
            return function()
                repeat
                    i = i + 1
                    local key = keys[i]
                    -- A key removed since the walk began is passed over
                    if key ~= nil and rawget(t, key) ~= nil then
                        return key, rawget(t, key)
                    end
                until key == nil
            end
        end,
    })
end

local Ledger = {}

-- The drops' names in the order they first dropped.
---@param drops string[]
---@return string[]
function Ledger.firstSeen(drops)
    local seen = orderedTable()
    for _, drop in ipairs(drops) do
        seen[drop] = true
    end
    local names = {}
    for name in pairs(seen) do
        names[#names + 1] = name
    end
    return names
end

-- How many of each drop, in the order they first dropped, as Java writes a map.
---@param drops string[]
---@return string
function Ledger.tally(drops)
    local counts = orderedTable()
    for _, drop in ipairs(drops) do
        counts[drop] = (counts[drop] or 0) + 1
    end
    return mapText(counts)
end

-- The drops still owed after the paid one, which is owed again last.
---@param drops string[]
---@param paid string
---@return string[]
function Ledger.owed(drops, paid)
    local owed = orderedTable()
    for _, drop in ipairs(drops) do
        owed[drop] = 1
    end
    owed[paid] = nil
    owed[paid] = 1
    local order = {}
    for drop in pairs(owed) do
        order[#order + 1] = drop
    end
    return order
end

-- The drops in alphabetical order, each once, as Java writes a set.
---@param drops string[]
---@return string
function Ledger.sorted(drops)
    local names = sortedTable()
    for _, drop in ipairs(drops) do
        names[drop] = true
    end
    return setText(names)
end

-- The drops by name, last first, with how many of each.
---@param counts table<string, integer>
---@return string[]
function Ledger.reversed(counts)
    local sorted = sortedTable(function(a, b)
        return b < a
    end)
    for name, count in pairs(counts) do
        sorted[name] = count
    end
    local lines = {}
    for key, value in pairs(sorted) do
        lines[#lines + 1] = formatStr("{} x{}", key, value)
    end
    return lines
end

-- The first and last drop by name, such as "ashes..coins".
---@param drops string[]
---@return string
function Ledger.ends(drops)
    local names = addElements(sortedTable(), drops)
    return formatStr("{}..{}", firstKey(names), lastKey(names))
end

-- Every drop of both runs, each once, by name.
---@param first string[]
---@param second table<string, true>
---@return string[]
function Ledger.merged(first, second)
    local all = addKeys({}, second)
    for _, value in ipairs(first) do
        all[value] = true
    end
    local names = keysOf(all)
    table.sort(names)
    return names
end

-- The counts of both runs added up, sorted by name.
---@param first table<string, integer>
---@param second table<string, integer>
---@return table<string, integer>
function Ledger.combined(first, second)
    local total = copyEntries(sortedTable(), first)
    for drop, count in pairs(second) do
        total[drop] = (total[drop] or 0) + count
    end
    return total
end

-- The stock with the drops' counts put over it.
---@param stock table<string, integer>
---@param drops table<string, integer>
---@return table<string, integer>
function Ledger.restocked(stock, drops)
    local result = copyEntries({}, stock)
    for key, value in pairs(drops) do
        result[key] = value
    end
    return result
end

-- The drops of either run, each once.
---@param first table<string, true>
---@param second table<string, true>
---@return table<string, true>
function Ledger.either(first, second)
    local all = {}
    for key in pairs(first) do
        all[key] = true
    end
    for key in pairs(second) do
        all[key] = true
    end
    return all
end

-- The names the stock has, then the labels of the drops, in one list.
---@param stock table<string, true>
---@param labels table<integer, string>
---@return string[]
function Ledger.named(stock, labels)
    local names = {}
    for key in pairs(stock) do
        names[#names + 1] = key
    end
    for _, value in pairs(labels) do
        names[#names + 1] = value
    end
    return names
end

-- The first and the last drop, such as "coins to bones".
---@param drops string[]
---@return string
function Ledger.firstAndLast(drops)
    return formatStr("{} to {}", drops[1], drops[#drops])
end

return Ledger
