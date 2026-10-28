-- src/ledger.lua
-- Drops in the order they came, and in sorted order.

local setText = require("utils/set_text")
local mapText = require("utils/map_text")
local orderedTable = require("utils/ordered_table")
local keysOf = require("utils/keys_of")
local addElements = require("utils/add_elements")
local addKeys = require("utils/add_keys")
local copyEntries = require("utils/copy_entries")
local firstKey = require("utils/first_key")
local lastKey = require("utils/last_key")
local sortedTable = require("utils/sorted_table")

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

-- How many of each drop, in the order they first dropped, as Kotlin writes a map.
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

-- The drops in alphabetical order, each once, as Kotlin writes a set.
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
