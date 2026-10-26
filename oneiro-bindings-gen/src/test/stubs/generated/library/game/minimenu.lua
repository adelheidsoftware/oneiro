---@meta

---The right-click menu, as the menu events hand it out.
---@class MiniMenu
---@field entries MiniMenuEntry[] (Read-only) Its entries; the last is what a left-click does.
MiniMenu = {}

---Swaps two entries.
---@param indexA integer The first entry's index.
---@param indexB integer The second entry's index.
function MiniMenu:Swap(indexA, indexB) end

---Adds an entry that runs the action when it is chosen.
---@param label string The entry's text.
---@param action fun(...: any) What it runs.
---@vararg any Values passed to the action.
function MiniMenu:Add(label, action, ...) end

---One entry of the right-click menu.
---@class MiniMenuEntry
---@field overrideText string? (Writeable) Text shown instead of its own.
---@field fullText string (Read-only) Its own text.
local MiniMenuEntry = {}
