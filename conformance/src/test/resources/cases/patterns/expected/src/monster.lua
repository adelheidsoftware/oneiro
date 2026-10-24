-- src/monster.lua
-- A creature that fights, with a combat level.

local Creature = require("src/creature")

local Monster = setmetatable({}, { __index = Creature })
Monster.__index = Monster

function Monster.new(name, level)
    local self = setmetatable(Creature.new(name), Monster)
    self.level = 0
    self.level = level
    return self
end

return Monster
