-- utils/add_keys.lua
-- Adds each key of the table to the set, and gives the set.

local function addKeys(set, t)
    for key in pairs(t) do
        set[key] = true
    end
    return set
end

return { addKeys = addKeys }
