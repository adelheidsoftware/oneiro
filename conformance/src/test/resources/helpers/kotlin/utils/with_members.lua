-- utils/with_members.lua
-- Gives the object the members of an object expression, which replace its class's where they share a name, and returns
-- it.

local function withMembers(object, members)
    for name, member in pairs(members) do
        object[name] = member
    end
    return object
end

return withMembers
