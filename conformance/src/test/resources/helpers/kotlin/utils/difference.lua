-- utils/difference.lua
-- Adds each element of a that b lacks to the set, and gives the set, as Kotlin's subtract.

local function difference(set, a, b)
    for key in pairs(a) do
        if b[key] == nil then
            set[key] = true
        end
    end
    return set
end

return difference
