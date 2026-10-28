-- utils/filled.lua
-- A new array of n copies of value, as Kotlin fills a new IntArray with zeros or a BooleanArray with false.

local function filled(n, value)
    local array = {}
    for i = 1, n do
        array[i] = value
    end
    return array
end

return { filled = filled }
