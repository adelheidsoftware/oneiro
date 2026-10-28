-- utils/to_integer.lua
-- Kotlin's toInt() of a Double: drops the fraction, rounding toward zero.

local function toInteger(d)
    if d >= 0 then
        return math.floor(d)
    end
    return math.ceil(d)
end

return toInteger
