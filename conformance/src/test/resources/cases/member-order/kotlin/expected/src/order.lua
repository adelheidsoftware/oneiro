-- src/order.lua
-- Module-level code runs top to bottom in Lua, so the functions it calls come first.

-- A new array of n copies of value, as Kotlin fills a new IntArray with zeros or a BooleanArray with false.
local function filled(n, value)
    local array = {}
    for i = 1, n do
        array[i] = value
    end
    return array
end

local Order = {}

-- Declared ahead: used above their definitions below.
local isOdd

local function square(value)
    return value * value
end

local function build(count)
    local squares = filled(count, 0)
    for i = 0, count - 1 do
        squares[i + 1] = square(i)
    end
    return squares
end

-- The first four squares, built when the module loads.
Order.SQUARES = build(4)

function Order.isEven(value)
    return value == 0 or isOdd(value - 1)
end

function isOdd(value)
    return value ~= 0 and Order.isEven(value - 1)
end

return Order
