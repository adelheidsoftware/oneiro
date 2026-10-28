-- src/loops.lua
-- Loop forms and their Lua equivalents.

local quotient = require("utils/quotient")

local Loops = {}

function Loops.sumBelow(limit)
    local total = 0
    for i = 0, limit - 1 do
        total = total + i
    end
    return total
end

function Loops.countDown(from)
    local steps = 0
    for i = from, 1, -1 do
        steps = steps + 1
    end
    return steps
end

function Loops.evens(limit)
    local count = 0
    for i = 0, limit, 2 do
        count = count + 1
    end
    return count
end

function Loops.sumOf(values)
    local total = 0
    for i = 1, #values do
        total = total + values[i]
    end
    return total
end

function Loops.weighted(values)
    local total = 0
    for i = 0, #values - 1 do
        total = total + i * values[i + 1]
    end
    return total
end

function Loops.skipOdd(values)
    local total = 0
    for _, value in ipairs(values) do
        if math.fmod(value, 2) ~= 0 then
            goto continue
        end
        total = total + value
        ::continue::
    end
    return total
end

function Loops.pairs(size)
    local found = 0
    for i = 0, size - 1 do
        for j = 0, size - 1 do
            if i == j then
                goto continue2
            end
            found = found + 1
            ::continue2::
        end
        if i == 0 then
            goto continue
        end
        found = found + 1
        ::continue::
    end
    return found
end

function Loops.halvings(value)
    local steps = 0
    local n = value
    while n > 1 do
        steps = steps + 1
        n = quotient(n, 2)
    end
    return steps
end

function Loops.keySum(scores, names)
    local total = 0
    for key, value in pairs(scores) do
        if names[key] ~= nil then
            total = total + value
        end
    end
    for name in pairs(names) do
        total = total + #name
    end
    return total
end

function Loops.diagonal(rows)
    local total = 0
    for key, value in pairs(rows) do
        for cellKey, cellValue in pairs(value) do
            if cellKey == key then
                total = total + cellValue
            end
        end
    end
    return total
end

function Loops.drain(start)
    local value = start
    repeat
        value = value - 1
    until not (value > 0)
    return value
end

return Loops
