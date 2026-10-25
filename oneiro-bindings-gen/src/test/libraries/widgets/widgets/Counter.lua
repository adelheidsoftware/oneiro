--- A counter that starts where it is told and counts up.
---@class widgets.Counter
---@field value integer The count so far.
---@field private step integer
local Counter = {}
Counter.__index = Counter

--- A counter at `start`, or at 0.
---@param start integer? Where to start
---@return widgets.Counter
function Counter.new(start)
    local self = setmetatable({ value = start or 0, step = 1 }, Counter)
    -- Block keywords in a body, and strings that hold them, end nothing
    if self.value < 0 then
        error("a counter starts at 0 or more; end")
    end
    for _ = 1, 2 do
        local _ = [[ end ]]
    end
    return self
end

--- Counts up by one, or by `by`.
---@param self widgets.Counter
---@param by integer?
---@return integer count the count after it
function Counter:increment(by)
    self.value = self.value + (by or self.step)
    return self.value
end

--- Counts up by `by`, and gives the counter back, for the next call.
---@param by integer
---@return self
function Counter:advance(by)
    self.value = self.value + by
    return self
end

---@package
function Counter:reset()
    self.value = 0
end

---@deprecated Use increment.
function Counter:bump()
    return self:increment()
end

return Counter
