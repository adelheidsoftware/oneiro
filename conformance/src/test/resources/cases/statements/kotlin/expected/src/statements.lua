-- src/statements.lua
-- Statements: locals, assignments, branches, loops, throws and printing.

-- A new array of n copies of value, as Kotlin fills a new IntArray with zeros or a BooleanArray with false.
local function filled(n, value)
    local array = {}
    for i = 1, n do
        array[i] = value
    end
    return array
end

local Statements = {}

-- The squares of 1 to 3, filled in when the module loads.
Statements.SQUARES = filled(3, 0)

for i = 1, #Statements.SQUARES do
    Statements.SQUARES[i] = i * i
end

function Statements.locals(seed)
    local a = seed
    local b = a + 1
    local c
    local d = a * b
    c = d - a
    c = c + 2
    c = c - 1
    c = c * 3
    c = c << 1
    do
        local scoped = c
        c = scoped + 1
    end
    return c
end

function Statements.grade(score)
    if score >= 90 then
        return "A"
    elseif score >= 50 then
        return "B"
    else
        return "C"
    end
end

function Statements.firstPowerAbove(limit)
    local power = 1
    while true do
        if power > limit then
            break
        end
        power = power * 2
    end
    return power
end

function Statements.checked(value)
    if value < 0 then
        error(formatStr("negative: {}", value))
    end
    if value > 100 then
        error("IllegalStateException")
    end
    return value
end

function Statements.report(name, count, ratio, done)
    print(name)
    print(count)
    print(formatStr("{}", ratio))
    print(tostring(done))
    print("")
    print(formatStr("done: {}", done))
end

-- A conditional whose true value may be nil is an if
function Statements.pick(first, a, b)
    local chosen
    if first then
        chosen = a
    else
        chosen = b
    end
    return chosen
end

function Statements.size(n, small)
    if n < 10 then
        return small
    elseif n < 100 then
        return nil
    else
        return "large"
    end
end

return Statements
