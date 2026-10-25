-- dev/cheats.lua
-- Development helpers, which name a type of the main source set's Lua.

local Cheats = {}

---@param tally Tally
function Cheats.fill(tally)
    tally.count = 100
end

return Cheats
