-- src/encounters.lua
-- What to say about the creatures met.

local Boss = require("src/boss")
local Monster = require("src/monster")
local Pet = require("src/pet")

-- Whether the value is an object of the class, or of a class that extends it, as Java's instanceof.
local function isInstance(value, class)
    if type(value) ~= "table" then
        return false
    end
    local current = getmetatable(value)
    while current ~= nil do
        if current == class then
            return true
        end
        -- A subclass's table looks up what it lacks in its superclass's
        local parent = getmetatable(current)
        current = parent and parent.__index
    end
    return false
end

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
