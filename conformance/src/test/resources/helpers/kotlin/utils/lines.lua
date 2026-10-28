-- utils/lines.lua
-- The text's lines, split at \r\n, \n or \r, as Kotlin's lines().

local function lines(text)
    local list = {}
    local start = 1
    while true do
        local first = text:find("[\r\n]", start)
        if first == nil then
            break
        end
        list[#list + 1] = text:sub(start, first - 1)
        -- \r\n ends one line, not two
        if text:sub(first, first + 1) == "\r\n" then
            start = first + 2
        else
            start = first + 1
        end
    end
    list[#list + 1] = text:sub(start)
    return list
end

return { lines = lines }
