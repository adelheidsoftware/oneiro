-- src/letters.lua
-- Letters and digits, one char at a time.

-- A new array of n copies of value, as Java fills a new array with zeros or false.
local function filled(n, value)
    local array = {}
    for i = 1, n do
        array[i] = value
    end
    return array
end

-- The 0-based position of the text in the string, or -1, as Java's String.indexOf.
local function stringIndexOf(s, text)
    local start = s:find(text, 1, true)
    if start == nil then
        return -1
    end
    return start - 1
end

local Letters = {}

-- The text with each lowercase letter moved along the alphabet, wrapping from z to a.
---@param text string
---@param shift integer
---@return string
function Letters.caesar(text, shift)
    local shifted = {}
    for c in text:gmatch(".") do
        if c >= "a" and c <= "z" then
            local index = math.fmod(string.byte(c) - string.byte("a") + shift, 26)
            shifted[#shifted + 1] = string.char(string.byte("a") + index)
        else
            shifted[#shifted + 1] = c
        end
    end
    return table.concat(shifted)
end

-- The capital letters of a name, such as "SC" for "Session Clock".
---@param name string
---@return string
function Letters.initials(name)
    local initials = ""
    for i = 1, #name do
        local c = name:sub(i, i)
        if c:find("%u") ~= nil then
            initials = initials .. c
        end
    end
    return initials
end

-- The sum of the digits in the text, skipping everything else.
---@param text string
---@return integer
function Letters.digitSum(text)
    local sum = 0
    for c in text:gmatch(".") do
        if c:find("%d") ~= nil then
            sum = sum + (string.byte(c) - string.byte("0"))
        end
    end
    return sum
end

-- How often each letter comes up, ignoring case.
---@param text string
---@return table<string, integer>
function Letters.letterCounts(text)
    local counts = {}
    for c in text:lower():gmatch(".") do
        if c:find("%a") ~= nil then
            counts[c] = (counts[c] or 0) + 1
        end
    end
    return counts
end

-- The letters from one to another, such as "abc" from 'a' to 'c'.
---@param from string
---@param to string
---@return string
function Letters.alphabet(from, to)
    local letters = {}
    local c = from
    while c <= to do
        letters[#letters + 1] = c
        c = string.char(string.byte(c) + 1)
    end
    return table.concat(letters)
end

-- The number Java gives the char.
---@param c string
---@return integer
function Letters.code(c)
    return string.byte(c)
end

-- The char with the number.
---@param code integer
---@return string
function Letters.fromCode(code)
    return string.char(code)
end

-- The grade for a tier from 1 to 3.
---@param tier integer
---@return string
function Letters.grade(tier)
    if tier == 1 then
        return "A"
    elseif tier == 2 then
        return "B"
    else
        return "F"
    end
end

-- The char with its number, such as "A=65".
---@param c string
---@return string
function Letters.describe(c)
    return formatStr("{}={}", c, string.byte(c))
end

-- The word with its first letter capitalised.
---@param word string
---@return string
function Letters.capitalised(word)
    return word:sub(1, 1):upper() .. word:sub(2)
end

-- Where the char is first found in the text, or -1.
---@param text string
---@param sought string
---@return integer
function Letters.find(text, sought)
    return stringIndexOf(text, sought)
end

-- The chars as text, a blank, and how many there are.
---@param chars string[]
---@return string
function Letters.joined(chars)
    local blanks = filled(1, "\000")
    blanks[1] = " "
    return formatStr("{}{}{}", table.concat(chars), table.concat(blanks), #chars)
end

return Letters
