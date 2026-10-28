-- src/kinds.lua
-- The kind of the value last looked at.

local Kinds = {}

local current = 0

-- Looks at a value.
---@param value any
function Kinds.look(value)
    current = value
end

-- What the log calls the value looked at: its kind.
---@return string
function Kinds.log()
    return math.type(current) == "integer" and "number" or "other"
end

return Kinds
