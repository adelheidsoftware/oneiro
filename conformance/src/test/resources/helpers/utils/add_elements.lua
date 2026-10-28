-- utils/add_elements.lua
-- Adds each element of the list to the set, and gives the set.

local function addElements(set, list)
    for _, value in ipairs(list) do
        set[value] = true
    end
    return set
end

return { addElements = addElements }
