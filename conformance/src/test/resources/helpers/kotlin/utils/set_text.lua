-- utils/set_text.lua
-- The set as Kotlin writes it, [a, b], in the order pairs walks it.

local function setText(set)
    local parts = {}
    for value in pairs(set) do
        parts[#parts + 1] = tostring(value)
    end
    return "[" .. table.concat(parts, ", ") .. "]"
end

return { setText = setText }
