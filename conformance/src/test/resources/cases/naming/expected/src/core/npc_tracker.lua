-- Names: an acronym in the file name, Lua keywords and globals as local names, and static imports.

local Limits = require("src/core/limits")

-- The number of entries in a map or set, as Java's size(); a Lua table keeps no count.
local function count(t)
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
        table_[end_] = Limits.capped(#names)
    end
    local count_ = count(table_)
    return count_ + Limits.MAX_TRACKED
end

return NPCTracker
