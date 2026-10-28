-- utils/split.lua
-- The parts of the text between the separators, as Java's String.split with a plain separator: with no limit, trailing
-- empty parts are dropped; a negative limit keeps them; a positive one makes at most that many parts.

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

return { split = split }
