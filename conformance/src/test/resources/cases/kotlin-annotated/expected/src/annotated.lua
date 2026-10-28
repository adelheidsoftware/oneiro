-- src/annotated.lua
-- Annotations on expressions, which the Lua leaves out.

local Annotated = {}

-- The names in a value, with the annotation on the cast a local takes.
---@param value any
---@return string[]
function Annotated.names(value)
    local names = value
    return names
end

-- The names in a value, or none, with the annotation on the cast a try gives a local.
---@param value any
---@return string[]
function Annotated.namesOrNone(value)
    local names
    local ok = pcall(function()
        names = value
    end)
    if not ok then
        names = {}
    end
    return names
end

-- Whether a level is high, with the annotation on the if a local takes.
---@param level integer
---@return boolean
function Annotated.high(level)
    local high
    if level > 50 then
        high = true
    else
        high = false
    end
    return high
end

return Annotated
