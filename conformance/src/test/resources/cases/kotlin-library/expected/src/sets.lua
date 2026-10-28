-- src/sets.lua
-- Kotlin's functions on maps and sets: their keys and values, clear, union, intersect and subtract.

local tableLength = require("utils/table_length").tableLength
local orderedTable = require("utils/ordered_table").orderedTable
local valuesOf = require("utils/values_of").valuesOf
local addKeys = require("utils/add_keys").addKeys
local clear = require("utils/clear").clear
local intersection = require("utils/intersection").intersection
local difference = require("utils/difference").difference

local Sets = {}

-- The skills trained, in the order they were first trained.
---@param xp table<string, integer>
---@return table<string, true>
function Sets.skills(xp)
    return addKeys(orderedTable(), xp)
end

-- The experience in each skill.
---@param xp table<string, integer>
---@return any
function Sets.amounts(xp)
    return valuesOf(xp)
end

-- How many skills have experience.
---@param xp table<string, integer>
---@return integer
function Sets.skillCount(xp)
    return tableLength(xp)
end

-- Whether the skill has experience.
---@param xp table<string, integer>
---@param skill string
---@return boolean
function Sets.trained(xp, skill)
    return xp[skill] ~= nil
end

-- The experience in the skill, or none.
---@param xp table<string, integer>
---@param skill string
---@return integer
function Sets.experience(xp, skill)
    return xp[skill] or 0
end

-- Forgets every skill.
---@param xp table<string, integer>
function Sets.forget(xp)
    clear(xp)
end

-- The skills either player trained.
---@param mine table<string, true>
---@param theirs table<string, true>
---@return table<string, true>
function Sets.either(mine, theirs)
    return addKeys(addKeys(orderedTable(), mine), theirs)
end

-- The skills both players trained.
---@param mine table<string, true>
---@param theirs table<string, true>
---@return table<string, true>
function Sets.both(mine, theirs)
    return intersection(orderedTable(), mine, theirs)
end

-- The skills only the first player trained.
---@param mine table<string, true>
---@param theirs table<string, true>
---@return table<string, true>
function Sets.onlyMine(mine, theirs)
    return difference(orderedTable(), mine, theirs)
end

-- The levels by skill, or none.
---@param levels table<string, integer>?
---@return table<string, integer>
function Sets.levels(levels)
    return levels or {}
end

-- Whether there are no levels.
---@param levels table<string, integer>?
---@return boolean
function Sets.noLevels(levels)
    return levels == nil or next(levels) == nil
end

-- Each skill at level 1.
---@param skills string[]
---@return table<string, integer>
function Sets.starting(skills)
    local map = orderedTable()
    for _, skill in ipairs(skills) do
        map[skill] = 1
    end
    return map
end

return Sets
