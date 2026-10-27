-- src/texts.lua
-- Kotlin's functions on text: replace, lines, its nullable forms, and tests of its chars.

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

local Texts = {}

-- The name with its spaces as underscores.
---@param name string
---@return string
function Texts.underscored(name)
    return replace(name, " ", "_")
end

-- How many lines the text has.
---@param text string
---@return integer
function Texts.lineCount(text)
    return #lines(text)
end

-- The name, or empty text for none.
---@param name string?
---@return string
function Texts.orNothing(name)
    return name or ""
end

-- Whether there is no name.
---@param name string?
---@return boolean
function Texts.unnamed(name)
    return name == nil or name == ""
end

-- Whether there is no name but spaces.
---@param name string?
---@return boolean
function Texts.blank(name)
    return name == nil or name:find("%S") == nil
end

-- How many times the letter is in the word.
---@param word string
---@param letter string
---@return integer
function Texts.letters(word, letter)
    local count = 0
    for it in word:gmatch(".") do
        if it == letter then
            count = count + 1
        end
    end
    return count
end

-- What the text is, by whether it has a digit.
---@param text string
---@return string
function Texts.kind(text)
    local any = false
    for it in text:gmatch(".") do
        if it:find("%d") ~= nil then
            any = true
            break
        end
    end
    if any then
        return "code"
    end
    return "word"
end

-- Whether every char of the text is a letter.
---@param text string
---@return boolean
function Texts.lettersOnly(text)
    local letters = true
    for it in text:gmatch(".") do
        if it:find("%a") == nil then
            letters = false
            break
        end
    end
    return letters
end

return Texts
