-- src/bank.lua
-- A bank whose tabs know the bank they belong to.

local Bank = {}
Bank.__index = Bank

-- One tab of the bank, which reads the bank's owner.
local Tab = {}
Tab.__index = Tab
Bank.Tab = Tab

function Tab.new(outer, name)
    local self = setmetatable({}, Tab)
    self.outer = outer
    self.name = name
    return self
end

-- The tab's title, with the owner's name.
---@return string
function Tab:title()
    return formatStr("{}'s {} ({} of {})", self.outer.owner, self.name, self.outer:tabCount(), #self.outer.tabs)
end

function Bank.new(owner)
    local self = setmetatable({}, Bank)
    self.owner = owner
    self.tabs = {}

    -- The first tab is made with the bank
    -- Every bank starts with its first tab
    self.tabs[#self.tabs + 1] = Tab.new(self, "main") -- named as the client names it
    -- More tabs open as the player adds them
    return self
end

-- Opens a new tab with the name.
---@param name string
---@return Tab
function Bank:open(name)
    local tab = Tab.new(self, name)
    self.tabs[#self.tabs + 1] = tab
    return tab
end

function Bank:tabCount()
    return #self.tabs
end

return Bank
