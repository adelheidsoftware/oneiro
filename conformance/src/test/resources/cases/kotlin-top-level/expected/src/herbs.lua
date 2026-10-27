-- src/herbs.lua
-- Herb names, tidied by a function private to this file.

local Herbs = {}

local SEPARATOR = ", "

-- The name in lower case.
---@param name string
---@return string
local function tidy(name)
    return name:lower()
end

-- The names, tidied and joined.
---@param names string[]
---@return string
function Herbs.joined(names)
    local parts = {}
    for _, it in ipairs(names) do
        parts[#parts + 1] = tidy(it)
    end
    return table.concat(parts, SEPARATOR)
end

return Herbs
