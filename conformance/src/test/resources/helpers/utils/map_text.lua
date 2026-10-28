-- utils/map_text.lua
-- The map as Java writes it, {a=1, b=2}, in Lua's order, which is no more fixed than a HashMap's.

local function mapText(map)
    local parts = {}
    for key, value in pairs(map) do
        parts[#parts + 1] = tostring(key) .. "=" .. tostring(value)
    end
    return "{" .. table.concat(parts, ", ") .. "}"
end

return mapText
