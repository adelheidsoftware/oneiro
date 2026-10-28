-- utils/string_index_of.lua
-- The 0-based position of the text in the string, or -1, as Kotlin's String.indexOf.

local function stringIndexOf(s, text)
    local start = s:find(text, 1, true)
    if start == nil then
        return -1
    end
    return start - 1
end

return { stringIndexOf = stringIndexOf }
