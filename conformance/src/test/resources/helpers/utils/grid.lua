-- utils/grid.lua
-- A new array of the sizes, each size an array of the next, with copies of value innermost, as Java fills a new array
-- of more than one dimension.

local function grid(value, size, ...)
    local array = {}
    for i = 1, size do
        if select("#", ...) == 0 then
            array[i] = value
        else
            array[i] = grid(value, ...)
        end
    end
    return array
end

return { grid = grid }
