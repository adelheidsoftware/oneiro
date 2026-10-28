-- utils/intersection.lua
-- Adds each element of a that b has too to the set, and gives the set, as Kotlin's intersect.

local function intersection(set, a, b)
    for key in pairs(a) do
        if b[key] ~= nil then
            set[key] = true
        end
    end
    return set
end

return { intersection = intersection }
