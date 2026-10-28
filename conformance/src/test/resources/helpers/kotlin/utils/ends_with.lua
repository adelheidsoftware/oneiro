-- utils/ends_with.lua
-- Whether the string ends with the suffix, as Kotlin's String.endsWith.

local function endsWith(s, suffix)
    return suffix == "" or s:sub(-#suffix) == suffix
end

return { endsWith = endsWith }
