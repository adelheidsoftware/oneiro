-- src/stock.lua
-- Maps and sets whose order nothing reads, which plain tables hold, beside one whose order the code reads.

local tableLength = require("utils/table_length").tableLength
local orderedTable = require("utils/ordered_table").orderedTable

local Stock = {}

local counts = {}
local seen = {}
local arrivals = orderedTable()
local prices = { guam = 10, ranarr = 25 }

-- Adds some of an item.
---@param item string
---@param amount integer
function Stock.add(item, amount)
    counts[item] = (counts[item] or 0) + amount
    seen[item] = true
    arrivals[item] = amount
end

-- How many of an item there are, or -1 for one never added.
---@param item string
---@return integer
function Stock.count(item)
    return seen[item] ~= nil and (counts[item] or 0) or -1
end

-- The items in the order they first arrived.
---@return string
function Stock.order()
    local text = ""
    for item in pairs(arrivals) do
        text = text .. item
    end
    return text
end

-- What an item is worth, or 0.
---@param item string
---@return integer
function Stock.price(item)
    return prices[item] or 0
end

-- How many different items there are, by a map of the function's own.
---@param items string[]
---@return integer
function Stock.kinds(items)
    local kinds = {}
    for _, item in ipairs(items) do
        kinds[item] = true
    end
    return tableLength(kinds)
end

return Stock
