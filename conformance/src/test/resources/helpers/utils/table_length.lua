-- utils/table_length.lua
-- The number of entries in a map or set, as Java's size(); a Lua table keeps no count.

local function tableLength(t)
    local n = 0
    for _ in pairs(t) do
        n = n + 1
    end
    return n
end

return { tableLength = tableLength }
