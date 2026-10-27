-- src/sets.lua
-- Kotlin's functions on maps and sets: their keys and values, clear, union, intersect and subtract.

-- The number of entries in a map or set, as Kotlin's size; a Lua table keeps no count.
local function tableLength(t)
    local n = 0
    for _ in pairs(t) do
        n = n + 1
    end
    return n
end

-- A table whose pairs walks its keys in the order they were first added, as Kotlin's maps and sets do, but for its hash
-- maps and hash sets. It holds its entries itself; its metatable keeps their order.
local function orderedTable()
    local keys = {}
    local added = {}
    return setmetatable({}, {
        __newindex = function(t, key, value)
            if value == nil then
                return
            end
            if added[key] then
                -- A key removed and added again goes to the end, as in a LinkedHashMap
                for i = #keys, 1, -1 do
                    if keys[i] == key then
                        table.remove(keys, i)
                        break
                    end
                end
            end
            added[key] = true
            keys[#keys + 1] = key
            rawset(t, key, value)
        end,
        __pairs = function(t)
            local i = 0
            return function()
                repeat
                    i = i + 1
                    local key = keys[i]
                    -- A key removed since it was added is passed over
                    if key ~= nil and rawget(t, key) ~= nil then
                        return key, rawget(t, key)
                    end
                until key == nil
            end
        end,
    })
end

-- The table's values as a list, in the order pairs gives them, as Kotlin copies a map's values into a list.
local function valuesOf(t)
    local list = {}
    for _, value in pairs(t) do
        list[#list + 1] = value
    end
    return list
end

-- Adds each key of the table to the set, and gives the set.
local function addKeys(set, t)
    for key in pairs(t) do
        set[key] = true
    end
    return set
end

-- Removes every element or entry of the table, as Kotlin's clear().
local function clear(t)
    for key in pairs(t) do
        t[key] = nil
    end
end

-- Adds each element of a that b has too to the set, and gives the set, as Kotlin's intersect.
local function intersection(set, a, b)
    for key in pairs(a) do
        if b[key] ~= nil then
            set[key] = true
        end
    end
    return set
end

-- Adds each element of a that b lacks to the set, and gives the set, as Kotlin's subtract.
local function difference(set, a, b)
    for key in pairs(a) do
        if b[key] == nil then
            set[key] = true
        end
    end
    return set
end

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
