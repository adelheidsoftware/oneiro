-- utils/trim.lua
-- The string without leading and trailing whitespace, as Kotlin's String.trim.

local function trim(s)
    return s:match("^%s*(.-)%s*$")
end

return { trim = trim }
