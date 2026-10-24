-- src/herbal.lua
-- A herb that says what it is, and orders by the level it needs.

local Describable = require("src/describable")

local Herbal = {}
Herbal.__index = Herbal

function Herbal.new(herb, level)
    local self = setmetatable({}, Herbal)
    self.level = 0
    self.herb = herb
    self.level = level
    return self
end

function Herbal:label()
    return self.herb
end

function Herbal:kind()
    return "herb"
end

function Herbal:compareTo(other)
    return self.level - other.level
end

Herbal.describe = Describable.describe

-- Orders by compareTo, as Java's natural order does
function Herbal.__lt(a, b)
    return a:compareTo(b) < 0
end

return Herbal
