-- src/core/npc_tracker.lua
-- Names: an acronym in the file name, Lua keywords, globals and helpers as local names, and static imports.

local Limits = require("src/core/limits")
local trim = require("utils/trim").trim
local tableLength = require("utils/table_length").tableLength

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
