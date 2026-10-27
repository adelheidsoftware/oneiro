-- src/tables.lua
-- Lists, maps and sets as plain Lua tables.

-- Whether the list holds the value, as Java's List.contains.
local function listContains(list, value)
    for _, item in ipairs(list) do
        if item == value then
            return true
        end
    end
    return false
end

-- The 0-based position of the value in the list, or -1, as Java's List.indexOf.
local function listIndexOf(list, value)
    for i, item in ipairs(list) do
        if item == value then
            return i - 1
        end
    end
    return -1
end

-- The number of entries in a map or set, as Java's size(); a Lua table keeps no count.
local function tableLength(t)
    local n = 0
    for _ in pairs(t) do
        n = n + 1
    end
    return n
end

local Tables = {}

function Tables.numbers()
    local numbers = {}
    numbers[#numbers + 1] = 3
    numbers[#numbers + 1] = 1
    table.insert(numbers, 1, 2)
    numbers[2] = 5
    table.sort(numbers, function(a, b)
        return a < b
    end)
    return numbers
end

function Tables.inspect(numbers)
    return listContains(numbers, 5) and listIndexOf(numbers, 2) == 0 and #numbers ~= 0 and numbers[1] == 2
end

function Tables.removeFirst(numbers)
    return table.remove(numbers, 1)
end

function Tables.scores()
    local scores = {}
    scores["ann"] = 3
    scores["bob"] = 5
    scores["bob"] = nil
    return scores
end

function Tables.lookup(scores, name)
    if scores[name] == nil then
        return (scores[name] or -1) + tableLength(scores)
    end
    return scores[name]
end

function Tables.seen(values)
    local seen = {}
    for _, value in ipairs(values) do
        seen[value] = true
    end
    seen[0] = nil
    return seen
end

function Tables.has(seen, value)
    return seen[value] ~= nil and next(seen) ~= nil and tableLength(seen) > 0
end

function Tables.doubled(source)
    local copy = table.move(source, 1, #source, 1, {})
    table.move(source, 1, #source, #copy + 1, copy)
    return copy
end

function Tables.fixed()
    return { "a", "b" }
end

function Tables.fixedMap()
    return { a = 1, b = 2 }
end

function Tables.fixedSet()
    return { x = true, y = true }
end

function Tables.sorted(values)
    local copy = table.move(values, 1, #values, 1, {})
    table.sort(copy)
    return copy
end

function Tables.weighed(scores)
    local total = 0
    for name in pairs(scores) do
        total = total + #name
    end
    for _, score in pairs(scores) do
        total = total + score
    end
    return total
end

return Tables
