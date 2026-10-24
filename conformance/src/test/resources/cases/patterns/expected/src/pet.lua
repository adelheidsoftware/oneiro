-- src/pet.lua
-- A creature that follows the player.

local Creature = require("src/creature")

local Pet = setmetatable({}, { __index = Creature })
Pet.__index = Pet

function Pet.new(name)
    local self = setmetatable(Creature.new(name), Pet)
    return self
end

return Pet
