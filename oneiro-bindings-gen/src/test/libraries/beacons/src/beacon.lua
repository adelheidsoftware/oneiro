--- A beacon, which a plugin lights.
---@class beacons.Beacon
---@field lit boolean Whether it is lit.
local Beacon = {}
Beacon.__index = Beacon

local count = 0

--- A beacon, not lit yet.
---@param label string
---@return beacons.Beacon
function Beacon.new(label)
    return setmetatable({ label = label, lit = false }, Beacon)
end

--- How many beacons are lit.
---@return integer
function Beacon.count()
    return count
end

function Beacon:light()
    self.lit = true
    count = count + 1
end

return Beacon
