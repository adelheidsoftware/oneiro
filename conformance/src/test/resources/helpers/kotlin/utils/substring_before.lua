-- utils/substring_before.lua
-- The text before the first delimiter, or all of it when there is none, as Kotlin's substringBefore.

local function substringBefore(text, delimiter)
    local first = text:find(delimiter, 1, true)
    if first == nil then
        return text
    end
    return text:sub(1, first - 1)
end

return { substringBefore = substringBefore }
