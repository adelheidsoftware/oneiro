-- Something that lives on the map, with a name.

local Creature = {}
Creature.__index = Creature

function Creature.new(name)
    local self = setmetatable({}, Creature)
    self.name = name
    return self
end

return Creature
