-- src/refs.lua
-- Function references as functions.

local Refs = {}

-- A sack that holds what it is given.
local Sack = {}
Sack.__index = Sack
Refs.Sack = Sack

function Sack.new()
    local self = setmetatable({}, Sack)
    self.held = {}
    return self
end

function Sack:put(item)
    self.held[#self.held + 1] = item
end

function Sack:size()
    return #self.held
end

-- Each name's length, by a library property.
---@param names string[]
---@return integer[]
function Refs.lengths(names)
    local list = {}
    for _, item in ipairs(names) do
        list[#list + 1] = #item
    end
    return list
end

-- The names that are not empty.
---@param names string[]
---@return integer
function Refs.filled(names)
    local count = 0
    for _, item in ipairs(names) do
        if item ~= "" then
            count = count + 1
        end
    end
    return count
end

-- The seeds of the names.
---@param names string[]
---@return table[]
function Refs.seeds(names)
    local list = {}
    for _, item in ipairs(names) do
        list[#list + 1] = { name = item }
    end
    return list
end

-- The seeds' names, by the data class's property.
---@param seeds table[]
---@return string[]
function Refs.names(seeds)
    local list = {}
    for _, item in ipairs(seeds) do
        list[#list + 1] = item.name
    end
    return list
end

-- Puts every item into the sack, and counts them.
---@param items string[]
---@return integer
function Refs.fill(items)
    local sack = Sack.new()
    for _, item in ipairs(items) do
        sack:put(item)
    end
    return sack:size()
end

-- The numbers in the texts.
---@param texts string[]
---@return integer[]
function Refs.numbers(texts)
    local list = {}
    for _, item in ipairs(texts) do
        list[#list + 1] = tonumber(item, 10)
    end
    return list
end

-- Copies the items into a list, through the list's add.
---@param items string[]
---@return string[]
function Refs.copied(items)
    local copy = {}
    for _, item in ipairs(items) do
        copy[#copy + 1] = item
    end
    return copy
end

-- Prints each line.
---@param lines string[]
function Refs.print(lines)
    for _, item in ipairs(lines) do
        print(item)
    end
end

-- The texts in upper case.
---@param texts string[]
---@return string[]
function Refs.shouted(texts)
    local list = {}
    for _, item in ipairs(texts) do
        list[#list + 1] = item:upper()
    end
    return list
end

-- The long names, collected by the list's add.
---@param names string[]
---@return string[]
function Refs.longNames(names)
    local found = {}
    for _, name in ipairs(names) do
        if #name > 3 then
            found[#found + 1] = name
        end
    end
    return found
end

return Refs
