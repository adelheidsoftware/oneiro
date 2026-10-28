-- utils/copy_entries.lua
-- Puts each entry of the source into the map, and gives the map.

local function copyEntries(map, source)
    for key, value in pairs(source) do
        map[key] = value
    end
    return map
end

return copyEntries
