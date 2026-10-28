-- utils/reversed.lua
-- A new list of the list's elements in the reverse order, as Kotlin's reversed().

local function reversed(list)
    local copy = {}
    for i = #list, 1, -1 do
        copy[#copy + 1] = list[i]
    end
    return copy
end

return { reversed = reversed }
