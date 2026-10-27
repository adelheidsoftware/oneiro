-- src/core/npc_tracker.lua
-- Names: an acronym in the file name, Lua keywords, globals and helpers as local names, and static imports.

local Limits = require("src/core/limits")

-- The string without leading and trailing whitespace, as Kotlin's String.trim.
local function trim(s)
    return s:match("^%s*(.-)%s*$")
end

-- The number of entries in a map or set, as Kotlin's size; a Lua table keeps no count.
local function tableLength(t)
    local n = 0
    for _ in pairs(t) do
        n = n + 1
    end
    return n
end

local NPCTracker = {}

function NPCTracker.track(names)
    local table_ = {}
    for _, end_ in ipairs(names) do
        local trim_ = trim(end_)
        table_[trim_] = Limits.capped(#names)
    end
    local count = tableLength(table_)
    return count + Limits.MAX_TRACKED
end

return NPCTracker
