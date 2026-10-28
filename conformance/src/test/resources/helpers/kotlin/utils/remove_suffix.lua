-- utils/remove_suffix.lua
-- The text without the suffix when it ends with it, as Kotlin's removeSuffix.

local function removeSuffix(text, suffix)
    if suffix ~= "" and text:sub(-#suffix) == suffix then
        return text:sub(1, -#suffix - 1)
    end
    return text
end

return removeSuffix
