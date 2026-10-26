-- src/core/signal.lua
-- A signal of the plugin's own, generic in the value it passes and in what its listeners give back.

---@class Signal<T, R>
---@field private listeners table
local Signal = {}
Signal.__index = Signal

---@return Signal
function Signal.new()
    return setmetatable({ listeners = {} }, Signal)
end

---Calls the listener with each value from now on.
---@param listener fun(value: T): R
function Signal:listen(listener)
    self.listeners[#self.listeners + 1] = listener
end

---Calls each listener with the value.
---@param value T
function Signal:fire(value)
    for _, listener in ipairs(self.listeners) do
        listener(value)
    end
end

return Signal
