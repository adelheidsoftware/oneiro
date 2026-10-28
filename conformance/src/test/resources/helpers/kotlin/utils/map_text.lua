-- utils/map_text.lua
-- The map as Kotlin writes it, {a=1, b=2}, in the order pairs walks it.

local function mapText(map)
    local parts = {}
    for key, value in pairs(map) do
        parts[#parts + 1] = tostring(key) .. "=" .. tostring(value)
    end
    return "{" .. table.concat(parts, ", ") .. "}"
end

return { mapText = mapText }
