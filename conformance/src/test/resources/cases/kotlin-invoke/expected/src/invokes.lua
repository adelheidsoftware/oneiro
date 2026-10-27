-- src/invokes.lua
-- Objects called as functions: Kotlin calls their invoke, and so does their Lua.

local Invokes = {}

-- Doubles a value, as a function the library calls.
local Doubler = {}
Doubler.__index = Doubler
Invokes.Doubler = Doubler

function Doubler.new()
    local self = setmetatable({}, Doubler)
    return self
end

function Doubler:invoke(value)
    return value * 2
end

-- Called as a function, as library code calls invoke
function Doubler:__call(...)
    return self:invoke(...)
end

-- Adds its step to a value, with an operator of its own rather than a function type.
local Stepper = {}
Stepper.__index = Stepper
Invokes.Stepper = Stepper

function Stepper.new(step)
    local self = setmetatable({}, Stepper)
    self.step = step
    return self
end

function Stepper:invoke(value)
    return value + self.step
end

-- One less than a value.
local Lower = {}
Invokes.Lower = Lower

function Lower.invoke(value)
    return value - 1
end

-- A machine of functions, which calls them from its method.
local Machine = {}
Machine.__index = Machine
Invokes.Machine = Machine

function Machine.new(doubler, stepper, callback)
    local self = setmetatable({}, Machine)
    self.doubler = doubler
    self.stepper = stepper
    self.callback = callback
    return self
end

-- The value through each of the machine's functions, and through some of its own, added up.
---@param value integer
---@return integer
function Machine:run(value)
    local plusOne = function(x)
        return x + 1
    end
    local twice = Doubler.new()
    local fromLocals = twice:invoke(value) + Doubler.new():invoke(value)
    local fromMembers = self.doubler:invoke(value) + self.stepper:invoke(value) + self.callback(value)
    local fromOthers = plusOne(value) + Lower.invoke(value)
    return fromLocals + fromMembers + fromOthers
end

-- The value through a machine's functions, called on it from outside.
---@param machine Machine
---@param value integer
---@return integer
function Invokes.through(machine, value)
    return machine.doubler:invoke(value) + machine.stepper:invoke(value) + machine.callback(value)
end

return Invokes
