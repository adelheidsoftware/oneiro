-- dev/cheats.lua
-- Development helpers, which name a type of the main source set's Lua and one of a library for development alone.

local Cheats = {}

---@param tally Tally
function Cheats.fill(tally)
    tally.count = 100
end

---@param gauge gauges.Gauge
function Cheats.watch(gauge) end

return Cheats
