-- src/ui/badge.lua
-- A badge that shows a tally, whose types name the RS3 API's, a module's of another folder and a library's.

---@class Badge
local Badge = {}

---@param parent ui.Component
---@param tally Tally
---@param format fun(count: integer, level: integer, last: boolean): string
function Badge.show(parent, tally, format) end

---@param counter widgets.Counter
function Badge.follow(counter) end

return Badge
