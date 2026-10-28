-- src/encounters.lua
-- What to say about the creatures met.

local Boss = require("src/boss")
local Monster = require("src/monster")
local Pet = require("src/pet")
local isInstance = require("utils/is_instance").isInstance

local Encounters = {}

-- How dangerous a creature is, in a word.
---@param creature Creature
---@return string
function Encounters.danger(creature)
    if getmetatable(creature) == Boss then
        return formatStr("deadly {}", creature.name)
    elseif isInstance(creature, Monster) and creature.level > 100 then
        return formatStr("strong {}", creature.name)
    elseif isInstance(creature, Monster) then
        return formatStr("weak {}", creature.name)
    elseif getmetatable(creature) == Pet then
        return formatStr("friendly {}", creature.name)
    else
        return "unknown"
    end
end

-- The combat level of a creature that fights, or 0.
---@param creature Creature
---@return integer
function Encounters.level(creature)
    if isInstance(creature, Monster) then
        return creature.level
    end
    return 0
end

-- Whether the value is text.
---@param value any
---@return boolean
function Encounters.isText(value)
    return type(value) == "string"
end

return Encounters
