-- src/planter.lua
-- Plants each herb it is given, keeping them in order.

local Planter = {}
Planter.__index = Planter

function Planter.new()
    local self = setmetatable({}, Planter)
    self.planted = {}
    return self
end

function Planter:accept(herb)
    self.planted[#self.planted + 1] = herb
end

function Planter:all()
    return self.planted
end

-- Called as a function, as library code calls accept
function Planter:__call(...)
    return self:accept(...)
end

return Planter
