-- main.lua
-- Remembers the last count it showed, through a function whose parameter has the property's name.

local shown = 0

local function show(shown_)
    shown = shown_
end

show(3)
log("shown", shown)
