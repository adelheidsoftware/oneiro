-- src/core/tally.lua
-- A count of herbs, as objects of a class.

---@class Tally
---@field count integer
local Tally = {}
Tally.__index = Tally

---@param start integer
---@return Tally
function Tally.new(start)
    return setmetatable({ count = start }, Tally)
end

---@param by integer
function Tally:add(by)
    self.count = self.count + by
end

return Tally
