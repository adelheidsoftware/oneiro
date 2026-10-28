-- utils/substring_after.lua
-- The text after the first delimiter, or all of it when there is none, as Kotlin's substringAfter.

local function substringAfter(text, delimiter)
    local _, last = text:find(delimiter, 1, true)
    if last == nil then
        return text
    end
    return text:sub(last + 1)
end

return substringAfter
