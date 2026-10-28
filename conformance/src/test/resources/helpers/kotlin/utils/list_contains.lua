-- utils/list_contains.lua
-- Whether the list holds the value, as Kotlin's List.contains.

local function listContains(list, value)
    for _, item in ipairs(list) do
        if item == value then
            return true
        end
    end
    return false
end

return { listContains = listContains }
