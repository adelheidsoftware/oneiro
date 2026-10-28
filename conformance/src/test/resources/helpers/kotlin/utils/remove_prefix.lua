-- utils/remove_prefix.lua
-- The text without the prefix when it starts with it, as Kotlin's removePrefix.

local function removePrefix(text, prefix)
    if text:sub(1, #prefix) == prefix then
        return text:sub(#prefix + 1)
    end
    return text
end

return removePrefix
