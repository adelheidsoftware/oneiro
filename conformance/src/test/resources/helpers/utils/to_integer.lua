-- utils/to_integer.lua
-- Java's (int) cast of a double: drops the fraction, rounding toward zero.

local function toInteger(d)
    if d >= 0 then
        return math.floor(d)
    end
    return math.ceil(d)
end

return { toInteger = toInteger }
