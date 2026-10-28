-- utils/pad_end.lua
-- The text before as many padChar as make it length long, as Kotlin's padEnd; longer text stays as it is.

local function padEnd(text, length, padChar)
    return text .. padChar:rep(length - #text)
end

return padEnd
