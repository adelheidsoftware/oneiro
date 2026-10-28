-- utils/compare.lua
-- -1, 0 or 1 as a comes before b, with it or after it, as Kotlin's compareTo, whose sign is all it promises.

local function compare(a, b)
    if a < b then
        return -1
    elseif b < a then
        return 1
    end
    return 0
end

return compare
