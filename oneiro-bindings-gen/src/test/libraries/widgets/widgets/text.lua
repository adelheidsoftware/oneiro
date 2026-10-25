-- Helpers for text, in the other forms a library defines its members in.

---@class widgets.text
local text = {}

--- How a line is aligned.
---@alias widgets.Align
---| "left" # Against the left edge
---| "right"

---@alias widgets.Size 1 | 2 | 3

---@enum (key) widgets.Colour
text.COLOURS = {
    WHITE = 0xFFFFFF, -- the default
    RED = 0xFF0000,
}

--- The widest a line may be, in characters.
text.MAX_WIDTH = 80

text.ELLIPSIS = "..."

--- The alignment a line has unless it asks for another.
---@type widgets.Align
text.align = "left"

---@param line string
---@return string
local function trim(line)
    return (line:gsub("^%s+", ""):gsub("%s+$", ""))
end
text.trim = trim

--- The first element of a list that passes a test.
---@generic T
---@param list T[]
---@param test fun(value: T): boolean
---@return T?
function text.find(list, test)
    for _, value in ipairs(list) do
        if test(value) then
            return value
        end
    end
    return nil
end

text.describe = function(self, prefix)
    return prefix .. tostring(self)
end

return text
