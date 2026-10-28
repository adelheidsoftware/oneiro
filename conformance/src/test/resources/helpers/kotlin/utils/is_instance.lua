-- utils/is_instance.lua
-- Whether the value is an object of the class, or of a class that extends it, as Kotlin's is.

local function isInstance(value, class)
    if type(value) ~= "table" then
        return false
    end
    local current = getmetatable(value)
    while current ~= nil do
        if current == class then
            return true
        end
        -- A subclass's table looks up what it lacks in its superclass's
        local parent = getmetatable(current)
        current = parent and parent.__index
    end
    return false
end

return isInstance
