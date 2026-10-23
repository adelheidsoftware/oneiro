-- Overloads that form a default chain become one Lua function with optional parameters.

local Overloads = {}
Overloads.__index = Overloads

local STEP = 1

---@param start? integer the first total
function Overloads.new(start)
    start = start or 0
    local self = setmetatable({}, Overloads)
    self.total = 0
    self.total = start
    return self
end

-- Adds to the total, or takes away.
---@param amount? integer how much
---@param negative? boolean whether to take it away
function Overloads:add(amount, negative)
    amount = amount or STEP
    if negative == nil then
        negative = false
    end
    self.total = self.total + (negative and -amount or amount)
end

function Overloads:sum()
    return self.total
end

-- A null default leaves the parameter nil
function Overloads.greet(name, title)
    return title == nil and formatStr("Hello {}", name) or formatStr("Hello {} {}", title, name)
end

local function clamp(value, limit)
    limit = limit or 10
    return math.min(value, limit)
end

function Overloads.limited(value)
    return clamp(value) + clamp(value, 3)
end

return Overloads
