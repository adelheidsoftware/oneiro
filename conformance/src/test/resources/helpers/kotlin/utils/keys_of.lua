-- utils/keys_of.lua
-- The table's keys as a list, in the order pairs gives them, as Kotlin copies a set or a map's keys into a list.

local function keysOf(t)
    local list = {}
    for key in pairs(t) do
        list[#list + 1] = key
    end
    return list
end

return keysOf
