-- src/functions.lua
-- Lambdas, functional interfaces, method references and generics.

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

return Functions
