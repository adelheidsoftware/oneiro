-- src/marker.lua
-- A mark that holds nothing, whose objects the board makes.

local Marker = {}
Marker.__index = Marker

function Marker.new()
    local self = setmetatable({}, Marker)
    return self
end

return Marker
