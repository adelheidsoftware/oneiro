-- Shared limits.

local Limits = {}

Limits.MAX_TRACKED = 3

function Limits.capped(value)
    return math.min(value, Limits.MAX_TRACKED)
end

return Limits
