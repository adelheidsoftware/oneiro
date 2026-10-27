-- src/inferred.lua
-- Locals, loop variables, lambda parameters and resources declared with var, whose types javac infers.

local Inferred = {}

-- A reader of a text, which counts when it is closed.
local Reader = {}
Reader.__index = Reader
Inferred.Reader = Reader

-- How many readers have been closed.
Reader.closes = 0

function Reader.new(text)
    local self = setmetatable({}, Reader)
    self.text = text
    return self
end

function Reader:size()
    return #self.text
end

function Reader:close()
    Reader.closes = Reader.closes + 1
end

function Inferred.sum(values)
    local total = 0
    for _, value in ipairs(values) do
        total = total + value
    end
    return total
end

function Inferred.sumBelow(limit)
    local total = 0
    for i = 0, limit - 1 do
        total = total + i
    end
    return total
end

function Inferred.weighted(scores)
    local total = 0
    for key, value in pairs(scores) do
        total = total + #key * value
    end
    return total
end

-- var gives these locals the types ArrayList and HashMap, where a declared type would be List and Map
function Inferred.longNames(names)
    local kept = {}
    for _, name in ipairs(names) do
        if #name > 3 then
            kept[#kept + 1] = name
        end
    end
    return kept
end

function Inferred.lengths(names)
    local lengths = {}
    for _, name in ipairs(names) do
        lengths[name] = #name
    end
    return lengths
end

function Inferred.combined(a, b)
    local sum = function(x, y)
        return x + y
    end
    return sum(a, b)
end

function Inferred.measured(text)
    local reader = Reader.new(text)
    local ok, result = pcall(function()
        return reader:size()
    end)
    reader:close()
    if not ok then
        error(result, 0)
    end
    return result
end

return Inferred
