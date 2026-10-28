-- utils/replace.lua
-- The text with each occurrence of old replaced by new, found as it is written, not as a pattern, as Kotlin's replace.

local function replace(text, old, new)
    if old == "" then
        -- Kotlin puts new before each character and after the last
        return (text:gsub("", (new:gsub("%%", "%%%%"))))
    end
    local parts = {}
    local start = 1
    while true do
        local first, last = text:find(old, start, true)
        if first == nil then
            break
        end
        parts[#parts + 1] = text:sub(start, first - 1)
        parts[#parts + 1] = new
        start = last + 1
    end
    parts[#parts + 1] = text:sub(start)
    return table.concat(parts)
end

return replace
