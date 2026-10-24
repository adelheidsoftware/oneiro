-- src/strings.lua
-- Strings in the house style: formatStr for building, table.concat for joining.

-- The 0-based position of the text in the string, or -1, as Kotlin's String.indexOf.
local function stringIndexOf(s, text)
    local start = s:find(text, 1, true)
    if start == nil then
        return -1
    end
    return start - 1
end

-- Whether the string ends with the suffix, as Kotlin's String.endsWith.
local function endsWith(s, suffix)
    return suffix == "" or s:sub(-#suffix) == suffix
end

-- The string without leading and trailing whitespace, as Kotlin's String.trim.
local function trim(s)
    return s:match("^%s*(.-)%s*$")
end

local Strings = {}

function Strings.greeting(name, level)
    return formatStr("Hello {}, level {}", name, level)
end

function Strings.joined(first, second)
    return first .. second
end

function Strings.tracking(id, ratio, done)
    return formatStr("id {} ratio {} done {}", tostring(id), ratio, done)
end

function Strings.formatted(count, share, big, mask)
    return formatStr(
        "{:>5}|{:<6}|{:.2f}|{}|{}|{}|%",
        count,
        "ab",
        share,
        string.format("%x", mask & 0xFFFFFFFF),
        string.format("%08X", mask & 0xFFFFFFFF),
        string.format("%+d", big)
    )
end

function Strings.listed(names)
    local text = { "names:" }
    for _, name in ipairs(names) do
        text[#text + 1] = " "
        text[#text + 1] = name
    end
    return table.concat(text)
end

function Strings.methods(text)
    if text == "" or text:sub(1, 1) ~= "x" then
        return text:upper()
    end
    return formatStr("{}{}{}{}", text:sub(2, 3), trim(text), stringIndexOf(text, "y"), #text)
end

function Strings.literal()
    return "a{b}c"
end

function Strings.braced(value)
    return formatStr("{{{}}}", value)
end

function Strings.counted(count)
    local text = "count"
    text = text .. formatStr("={}", count)
    return text
end

function Strings.compare(a, b)
    return a == b or a:lower() == b:lower() or a:find(b, 1, true) ~= nil or a:sub(-4) == ".lua" or endsWith(a, b)
end

function Strings.converted(count, ratio, id, flag)
    local number = tostring(count) .. tostring(count)
    return number .. formatStr("{}", ratio) .. tostring(flag) .. tostring(id)
end

function Strings.changed(text)
    return text:rep(2) .. text:lower()
end

function Strings.parsed(text)
    return tonumber(text, 10) + tonumber("7", 10)
end

function Strings.joinedAll(names)
    return table.concat(names, ", ") .. table.concat({ "a", "b" }, "-")
end

function Strings.numbered(name, count)
    return formatStr("{1} {0}", name, count)
end

function Strings.specifiers(text, value)
    return formatStr("{:.3} {:.1e}\n", text, value)
end

function Strings.built(flag, ratio)
    local text = {}
    text[#text + 1] = tostring(flag)
    text[#text + 1] = formatStr("{}", ratio)
    return table.concat(text)
end

return Strings
