-- Interop with existing Lua: names it calls, tables it passes, and a hand-written module it provides.

local Levels = require("src/core/levels")

local Tracker = {}
Tracker.__index = Tracker

function Tracker.new()
    local self = setmetatable({}, Tracker)
    self.seen = 0
    return self
end

function Tracker:Ingest(positions)
    for _, position in ipairs(positions) do
        local sprite = position.spriteId
        if sprite ~= nil and position.visible then
            self.seen = self.seen + 1
        end
    end
end

function Tracker:Summary()
    return { seen = self.seen, level = Levels.fromPenalty(self.seen) + Levels.PERFECT }
end

return Tracker
