-- Test-only stand-in for the RS3 client's formatStr, so that generated modules run under a standalone Lua 5.4. It
-- reproduces what a probe plugin recorded on client 951-1: every number is formatted as a double, in fmtlib's
-- shortest form (plain from 1e-4 up to 1e16, exponent form outside); {} and {0} placeholders; {{ and }}; and the
-- options Oneiro emits, [align][sign][0][width][.precision][f|e]. Like the client, a bad format prints an error and
-- returns nil. Never shipped.

-- A finite double in fmtlib's shortest round-trip form: 2 for 2.0, 0.30000000000000004, 1e+16, 1e-05.
local function shortest(x)
    if x ~= x then
        return string.format("%f", x)
    elseif x == math.huge then
        return "inf"
    elseif x == -math.huge then
        return "-inf"
    elseif x == 0 then
        return 1 / x < 0 and "-0" or "0"
    end
    local text
    for precision = 0, 16 do
        text = string.format("%." .. precision .. "e", x)
        if tonumber(text) == x then
            break
        end
    end
    local sign, mantissa, exponent = text:match("^(-?)([%d.]+)e([-+]%d+)$")
    local digits = mantissa:gsub("%.", ""):gsub("0+$", "")
    if digits == "" then
        digits = "0"
    end
    local power = tonumber(exponent)
    if power >= -4 and power < 16 then
        if power >= 0 then
            local whole = digits:sub(1, power + 1)
            whole = whole .. string.rep("0", power + 1 - #whole)
            local fraction = digits:sub(power + 2)
            return sign .. whole .. (fraction ~= "" and "." .. fraction or "")
        end
        return sign .. "0." .. string.rep("0", -power - 1) .. digits
    end
    local rest = #digits > 1 and "." .. digits:sub(2) or ""
    local powerText = (power < 0 and "-" or "+") .. string.format("%02d", math.abs(power))
    return sign .. digits:sub(1, 1) .. rest .. "e" .. powerText
end

-- A value with no options: numbers as doubles, strings as they are, booleans and nil by name, others by type name.
local function plain(value)
    local kind = type(value)
    if kind == "number" then
        return shortest(value * 1.0)
    elseif kind == "string" then
        return value
    elseif kind == "boolean" or kind == "nil" then
        return tostring(value)
    end
    return kind
end

local function padded(text, width, align)
    local length = utf8.len(text) or #text
    local missing = width - length
    if missing <= 0 then
        return text
    elseif align == "<" then
        return text .. string.rep(" ", missing)
    elseif align == "^" then
        local left = missing // 2
        return string.rep(" ", left) .. text .. string.rep(" ", missing - left)
    end
    return string.rep(" ", missing) .. text
end

-- A value with options; nil when the options do not fit the value, as for {:.2f} on a string.
local function withOptions(value, options)
    local align, sign, zero, width, precision, kind = options:match("^([<>^]?)([+ ]?)(0?)(%d*)(%.?%d*)([fe]?)$")
    if align == nil then
        return nil
    end
    local isNumber = type(value) == "number"
    local digits = precision:sub(2)
    local text
    if kind ~= "" then
        if not isNumber then
            return nil
        end
        text = string.format("%" .. sign .. "." .. (digits ~= "" and digits or "6") .. kind, value * 1.0)
    elseif isNumber then
        text = shortest(value * 1.0)
        if sign ~= "" and value >= 0 then
            text = sign .. text
        end
    else
        if sign ~= "" or zero ~= "" then
            return nil
        end
        text = plain(value)
        if digits ~= "" then
            text = text:sub(1, utf8.offset(text, tonumber(digits) + 1) - 1)
        end
    end
    if width == "" then
        return text
    end
    local size = tonumber(width)
    if zero ~= "" and isNumber and align == "" then
        local signPart, rest = text:match("^([-+ ]?)(.*)$")
        return signPart .. string.rep("0", size - #text) .. rest
    end
    return padded(text, size, align ~= "" and align or (isNumber and ">" or "<"))
end

local function fail(message)
    print("[ERROR] formatStr: " .. message)
    return nil
end

function formatStr(format, ...)
    local arguments = table.pack(...)
    local pieces = {}
    local automatic = 1
    local position = 1
    while position <= #format do
        local char = format:sub(position, position)
        local following = format:sub(position + 1, position + 1)
        if char == "{" and following == "{" then
            pieces[#pieces + 1] = "{"
            position = position + 2
        elseif char == "}" and following == "}" then
            pieces[#pieces + 1] = "}"
            position = position + 2
        elseif char == "{" then
            local close = format:find("}", position, true)
            if close == nil then
                return fail("invalid format string")
            end
            local index, options = format:sub(position + 1, close - 1):match("^(%d*):?(.*)$")
            local argument
            if index ~= "" then
                argument = tonumber(index) + 1
            else
                argument = automatic
                automatic = automatic + 1
            end
            if argument > arguments.n then
                return fail("argument not found")
            end
            local text = options == "" and plain(arguments[argument]) or withOptions(arguments[argument], options)
            if text == nil then
                return fail("invalid format specifier")
            end
            pieces[#pieces + 1] = text
            position = close + 1
        elseif char == "}" then
            return fail("invalid format string")
        else
            pieces[#pieces + 1] = char
            position = position + 1
        end
    end
    return table.concat(pieces)
end
