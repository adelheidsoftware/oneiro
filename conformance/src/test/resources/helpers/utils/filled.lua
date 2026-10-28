-- utils/filled.lua
-- A new array of n copies of value, as Java fills a new array with zeros or false.

local function filled(n, value)
    local array = {}
    for i = 1, n do
        array[i] = value
    end
    return array
end

return filled
