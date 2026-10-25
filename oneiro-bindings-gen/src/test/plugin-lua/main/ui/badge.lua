-- src/ui/badge.lua
-- A badge that shows a tally, whose types name the RS3 API's and a module's of another folder.

---@class Badge
local Badge = {}

---@param parent ui.Component
---@param tally Tally
---@param format fun(count: integer, level: integer, last: boolean): string
function Badge.show(parent, tally, format) end

return Badge
