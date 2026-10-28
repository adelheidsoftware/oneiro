-- utils/pad_start.lua
-- The text after as many padChar as make it length long, as Kotlin's padStart; longer text stays as it is.

local function padStart(text, length, padChar)
    return padChar:rep(length - #text) .. text
end

return padStart
