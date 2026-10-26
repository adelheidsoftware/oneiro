-- A fixture of a library plugin's own code: its main.lua returns the table that the client gives the plugins that
-- depend on it as the global of their alias.

local Beacon = require("src/beacon")

local beacons = {}

beacons.version = "1.2.0"
beacons.Beacon = Beacon

--- How many beacons are lit.
---@return integer
function beacons.lit()
    return Beacon.count()
end

return beacons
