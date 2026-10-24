-- Remembers the last count it showed, through a method whose parameter has the field's name.

local shown = 0

local function show(shown_)
    shown = shown_
end

show(3)
log("shown", shown)
