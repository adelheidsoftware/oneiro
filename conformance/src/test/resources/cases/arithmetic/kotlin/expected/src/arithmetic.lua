-- src/arithmetic.lua
-- Integer arithmetic keeps Kotlin's rounding toward zero.

-- Integer division that rounds toward zero, as Kotlin's / does; Lua's // rounds down.
local function quotient(a, b)
    local q = a // b
    if q < 0 and q * b ~= a then
        q = q + 1
    end
    return q
end

-- Kotlin's toInt() of a Double: drops the fraction, rounding toward zero.
local function toInteger(d)
    if d >= 0 then
        return math.floor(d)
    end
    return math.ceil(d)
end

local Arithmetic = {}

local SLOTS = 5

-- Kotlin's / on numbers that may be negative.
---@param a integer
---@param b integer
---@return integer
function Arithmetic.divide(a, b)
    return quotient(a, b)
end

-- Kotlin's % on numbers that may be negative.
---@param a integer
---@param b integer
---@return integer
function Arithmetic.remainder(a, b)
    return math.fmod(a, b)
end

function Arithmetic.half(items)
    return #items // 2 + #items % 2
end

function Arithmetic.shifted(value)
    return value // 16 + ((value & 0xFFFFFFFF) >> 28) + (value << 1) + (SLOTS >> 1)
end

function Arithmetic.ratio(part, whole)
    return part / whole
end

function Arithmetic.truncated(value)
    return toInteger(value)
end

function Arithmetic.floored(a, b)
    return a // b + a % b
end

return Arithmetic
