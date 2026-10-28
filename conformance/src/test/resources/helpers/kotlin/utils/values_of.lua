-- utils/values_of.lua
-- The table's values as a list, in the order pairs gives them, as Kotlin copies a map's values into a list.

local function valuesOf(t)
    local list = {}
    for _, value in pairs(t) do
        list[#list + 1] = value
    end
    return list
end

return { valuesOf = valuesOf }
