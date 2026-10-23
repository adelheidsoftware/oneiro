local subscriptions = {}
local function event(name)
    return {
        Subscribe = function(reference, callback)
            subscriptions[name .. "/" .. reference] = callback
        end,
        Unsubscribe = function(reference)
            subscriptions[name .. "/" .. reference] = nil
        end,
    }
end
Event = { Logic = event("Logic"), InventoryUpdated = event("InventoryUpdated") }

local Ticker = require("src/ticker")

local ticker = Ticker.new()
ticker:start()
subscriptions["Logic/ticker.logic"]({ logicTick = 1 })
subscriptions["Logic/ticker.logic"]({ logicTick = 2 })
assert(ticker:tickCount() == 2, "the method reference calls the object's own method")
subscriptions["InventoryUpdated/ticker.inventory"]({ slotsUpdated = { 3, 7, 9 } })
assert(#ticker:rememberedSlots() == 3, "the private method is called with the object")

local seen = {}
assert(ticker:each(function(slot)
    seen[#seen + 1] = slot
end) == 3 and #seen == 3, "every slot when nothing fails")
assert(ticker:each(function(slot)
    if slot == 7 then
        error("seven")
    end
end) == 1, "the count up to the first failure")

ticker:stop()
assert(next(subscriptions) == nil, "both subscriptions are removed")
