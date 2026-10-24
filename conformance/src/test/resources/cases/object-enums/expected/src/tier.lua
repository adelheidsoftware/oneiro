-- src/tier.lua
-- How hard a patch is to farm, from the easiest.

local Tier = {}
Tier.__index = Tier

function Tier.new(name, ordinal)
    local self = setmetatable({}, Tier)
    self.name = name
    self.ordinal = ordinal
    return self
end

Tier.EASY = Tier.new("EASY", 0)
Tier.HARD = Tier.new("HARD", 1)
Tier.ELITE = Tier.new("ELITE", 2)

-- Orders the constants as they are declared, as Java's natural order does
function Tier.__lt(a, b)
    return a.ordinal < b.ordinal
end

return Tier
