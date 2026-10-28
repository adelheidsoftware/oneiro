-- utils/split.lua
-- The parts of the text between the separators, as Kotlin's String.split with a plain separator, empty ones among them;
-- the limit is -1 for any number of parts, or the most parts to make.

local function split(text, separator, limit)
    if limit == nil then
        limit = 0
    end
    local parts = {}
    local start = 1
    while limit <= 0 or #parts < limit - 1 do
        local first, last = text:find(separator, start, true)
        if first == nil then
            break
        end
        parts[#parts + 1] = text:sub(start, first - 1)
        start = last + 1
    end
    if #parts == 0 then
        return { text }
    end
    parts[#parts + 1] = text:sub(start)
    if limit == 0 then
        while parts[#parts] == "" do
            parts[#parts] = nil
        end
    end
    return parts
end

return split
