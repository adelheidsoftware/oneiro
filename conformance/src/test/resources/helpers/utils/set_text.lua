-- utils/set_text.lua
-- The set as Java writes it, [a, b], in Lua's order, which is no more fixed than a HashSet's.

local function setText(set)
    local parts = {}
    for value in pairs(set) do
        parts[#parts + 1] = tostring(value)
    end
    return "[" .. table.concat(parts, ", ") .. "]"
end

return { setText = setText }
