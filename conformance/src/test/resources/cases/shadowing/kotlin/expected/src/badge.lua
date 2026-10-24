-- src/badge.lua
-- A badge of some type, at a level.

local Badge = {}
Badge.__index = Badge

function Badge.new(type_, level)
    local self = setmetatable({}, Badge)
    self.type = type_
    self.level = level
    if level < 0 then
        error(formatStr("level {}", level))
    end
    return self
end

-- The badge as text, such as "gold 2".
---@return string
function Badge:describe()
    return formatStr("{} {}", self.type, self.level)
end

return Badge
