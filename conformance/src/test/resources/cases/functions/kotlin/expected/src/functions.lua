-- src/functions.lua
-- Lambdas, function types, fun interfaces, function references and generics.

local Functions = {}

function Functions.keep(items, keep)
    local kept = {}
    for _, item in ipairs(items) do
        if keep(item) then
            kept[#kept + 1] = item
        end
    end
    return kept
end

function Functions.matching(values, check)
    local matching = 0
    for _, value in ipairs(values) do
        if check(value) then
            matching = matching + 1
        end
    end
    return matching
end

function Functions.isEven(value)
    return math.fmod(value, 2) == 0
end

function Functions.evens(values)
    return Functions.keep(values, function(value)
        return math.fmod(value, 2) == 0
    end)
end

function Functions.evenCount(values)
    return Functions.matching(values, Functions.isEven)
end

function Functions.applied(start)
    local twice = function(x)
        return x * 2
    end
    local sum = function(a, b)
        local total = a + b
        return total
    end
    local name = function()
        return "functions"
    end
    local nothing = function() end
    nothing()
    return sum(twice(start), #name())
end

function Functions.each(values, sink)
    for _, value in ipairs(values) do
        sink(value)
    end
end

function Functions.collected(values)
    local found = {}
    Functions.each(values, function(value)
        found[#found + 1] = value
    end)
    return found
end

function Functions.noted(values)
    local noted = {}
    local note = function(value)
        noted[#noted + 1] = value
    end
    local ignore = function() end
    for _, value in ipairs(values) do
        note(value)
        ignore(value)
    end
    return noted
end

return Functions
