-- An object whose methods are subscribed as callbacks.

local Ticker = {}
Ticker.__index = Ticker

function Ticker.new()
    local self = setmetatable({}, Ticker)
    self.ticks = 0
    self.slots = {}
    return self
end

local function remember(self, event)
    for _, slot in ipairs(event.slotsUpdated) do
        self.slots[#self.slots + 1] = slot
    end
end

function Ticker:start()
    Event.Logic.Subscribe("ticker.logic", function(event)
        self:tick(event)
    end)
    Event.InventoryUpdated.Subscribe("ticker.inventory", function(event)
        remember(self, event)
    end)
end

function Ticker:stop()
    Event.Logic.Unsubscribe("ticker.logic")
    Event.InventoryUpdated.Unsubscribe("ticker.inventory")
end

function Ticker:tick(event)
    self.ticks = self.ticks + 1
end

function Ticker:tickCount()
    return self.ticks
end

function Ticker:rememberedSlots()
    return self.slots
end

-- Runs the action for each slot, and stops at the first that fails.
---@param action function
---@return integer # how many succeeded
function Ticker:each(action)
    local done = 0
    for _, slot in ipairs(self.slots) do
        local ok = pcall(function()
            action(slot)
            done = done + 1
        end)
        if not ok then
            return done
        end
    end
    return done
end

return Ticker
