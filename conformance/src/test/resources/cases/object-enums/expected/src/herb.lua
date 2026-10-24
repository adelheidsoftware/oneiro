-- Herbs a patch can grow, each with its seed and the Farming level it needs.

local Herb = {}
Herb.__index = Herb

function Herb.new(name, ordinal, seedId, requiredLevel)
    local self = setmetatable({}, Herb)
    self.name = name
    self.ordinal = ordinal
    self.seedId = 0
    self.requiredLevel = 0
    self.seedId = seedId
    self.requiredLevel = requiredLevel
    return self
end

-- The first herb a farmer grows.
Herb.GUAM = Herb.new("GUAM", 0, 5291, 9)
Herb.MARRENTILL = Herb.new("MARRENTILL", 1, 5292, 14) -- a little later
Herb.RANARR = Herb.new("RANARR", 2, 5295, 32)
-- Only Ranarr has a note
Herb.RANARR.note = "the prayer herb"

-- Ranarr is known for prayer, not for its seed
function Herb.RANARR:describe()
    return self.note
end

-- Whether a farmer of the level can plant it.
---@param farming integer
---@return boolean
function Herb:canPlant(farming)
    return farming >= self.requiredLevel
end

-- What the herb is, for the log.
---@return string
function Herb:describe()
    return formatStr("{} from seed {}", self.name:lower(), self.seedId)
end

-- The herb after this one, or this one when it is the last.
---@return Herb
function Herb:next()
    local all = { Herb.GUAM, Herb.MARRENTILL, Herb.RANARR }
    if self.ordinal + 1 < #all then
        return all[self.ordinal + 2]
    end
    return self
end

-- Orders the constants as they are declared, as Java's natural order does
function Herb.__lt(a, b)
    return a.ordinal < b.ordinal
end

return Herb
