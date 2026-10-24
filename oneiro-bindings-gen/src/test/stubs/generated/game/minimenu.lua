---@meta

---The right-click menu, as the menu events hand it out.
---@class MiniMenu
---@field entries MiniMenuEntry[] Its entries; the last is what a left-click does.
MiniMenu = {}

---Swaps two entries.
---@param indexA Int The first entry's index.
---@param indexB Int The second entry's index.
function MiniMenu:Swap(indexA, indexB) end

---Adds an entry that runs the action when it is chosen.
---@param label String The entry's text.
---@param action fun(...: any) What it runs.
---@vararg any Values passed to the action.
function MiniMenu:Add(label, action, ...) end

---One entry of the right-click menu.
---@class MiniMenuEntry
---@field overrideText String Text shown instead of its own.
---@field fullText String Its own text.
local MiniMenuEntry = {}
