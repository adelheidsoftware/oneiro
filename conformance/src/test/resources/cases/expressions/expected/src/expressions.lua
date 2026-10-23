-- Expressions: literals, operators, conditionals, casts and arrays.

-- Java's (int) cast of a double: drops the fraction, rounding toward zero.
local function toInteger(d)
    if d >= 0 then
        return math.floor(d)
    end
    return math.ceil(d)
end

-- A new array of n copies of value, as Java fills a new array with zeros or false.
local function filled(n, value)
    local array = {}
    for i = 1, n do
        array[i] = value
    end
    return array
end

local Expressions = {}

function Expressions.integers()
    return { 42, 42, 1000, 0xFF, 5, 15, -7 }
end

function Expressions.decimals()
    return { 1.5, 1.5, 1e3, 2.0, 0.5, 3.0, 16.0 }
end

function Expressions.texts()
    return {
        "text\n",
        "é",
        'say "hi"',
        [[
first
second]],
    }
end

function Expressions.nothing()
    return nil
end

function Expressions.logic(a, b, x)
    local both = a and b
    local either = a or b
    local differ = a ~= b
    return both or either and not differ or x > 3
end

function Expressions.bits(x)
    return x & 0xF | x ~ 3 | ~x | x << 2
end

function Expressions.conditional(n, name)
    local sign = n < 0 and "negative" or "positive"
    local shown
    if name ~= nil then
        shown = name
    else
        shown = "nobody"
    end
    return formatStr("{} {}", sign, shown)
end

function Expressions.casts(d, big)
    local truncated = toInteger(d)
    local widened = truncated
    local asDouble = big
    local boxed = "text"
    return widened + toInteger(asDouble)
end

function Expressions.arrays(n)
    local values = { 1, 2, 3 }
    local flags = filled(n, false)
    local zeros = filled(2, 0.0)
    values[1] = values[3] + #values
    return #flags + #zeros + values[1]
end

function Expressions.limits()
    return { 2147483647, -2147483648, math.maxinteger, math.mininteger }
end

return Expressions
