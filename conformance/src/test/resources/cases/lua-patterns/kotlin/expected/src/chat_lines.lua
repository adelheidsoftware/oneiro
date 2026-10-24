-- src/chat_lines.lua
-- Chat lines read with Lua patterns, through LuaString where Kotlin would use Regex.

local ChatLines = {}

-- The fish a catch message names, such as "shark" in "You catch a shark.", or null for another line.
---@param line string
---@return string?
function ChatLines.caught(line)
    return line:match("^You catch an? (.+)%.$")
end

-- The amount and the item of a drop message, such as "3" and "rune bars" in "Drop: 3 x rune bars".
---@param line string
---@return string[]
function ChatLines.drop(line)
    return { line:match("^Drop: (%d+) x (.+)$") }
end

-- Where the line's first number starts, or -1.
---@param line string
---@return integer
function ChatLines.firstNumber(line)
    return (line:find("%d") or 0) - 1
end

-- The line with each number hidden, such as "Level #" for "Level 99".
---@param line string
---@return string
function ChatLines.masked(line)
    return (line:gsub("%d+", "#"))
end

-- The words of the line.
---@param line string
---@return string[]
function ChatLines.words(line)
    local words = {}
    for word in line:gmatch("%a+") do
        words[#words + 1] = word
    end
    return words
end

return ChatLines
