-- utils/put_entries.lua
-- Puts the keys and values the list holds in turn into the map, in order, and gives the map.

local function putEntries(map, list)
    for i = 1, #list, 2 do
        map[list[i]] = list[i + 1]
    end
    return map
end

return { putEntries = putEntries }
