-- utils/list_index_of.lua
-- The 0-based position of the value in the list, or -1, as Java's List.indexOf.

local function listIndexOf(list, value)
    for i, item in ipairs(list) do
        if item == value then
            return i - 1
        end
    end
    return -1
end

return listIndexOf
