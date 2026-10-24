-- src/targets.lua
-- A name to follow, which may be missing.

local Targets = {}

-- The first name that starts with the prefix, if there is one.
---@param names string[]
---@param prefix string
---@return string?
function Targets.firstWith(names, prefix)
    for _, name in ipairs(names) do
        if name:sub(1, #prefix) == prefix then
            return name
        end
    end
    return nil
end

-- The name to show: the first one with an A, or "nobody".
---@param names string[]
---@return string
function Targets.shown(names)
    return Targets.firstWith(names, "A") or "nobody"
end

-- Whether any name starts with the prefix.
---@param names string[]
---@param prefix string
---@return boolean
function Targets.has(names, prefix)
    return Targets.firstWith(names, prefix) ~= nil
end

-- The length of the first name with a B, or 0.
---@param names string[]
---@return integer
function Targets.length(names)
    local found = Targets.firstWith(names, "B")
    if found == nil then
        return 0
    end
    return #found
end

-- Greets the first name with a C, and the first with a D or nobody.
---@param names string[]
---@param greetings string[]
function Targets.greet(names, greetings)
    local name = Targets.firstWith(names, "C")
    if name ~= nil then
        greetings[#greetings + 1] = formatStr("hi {}", name)
    end
    local other = Targets.firstWith(names, "D")
    if other ~= nil then
        local found = other
        greetings[#greetings + 1] = formatStr("hey {}", found)
    else
        greetings[#greetings + 1] = "nobody"
    end
end

-- The first name with an E, which must be there.
---@param names string[]
---@return string
function Targets.required(names)
    return Targets.firstWith(names, "E") or error("no name with an E")
end

-- Whether a setting is on: one nobody set is on.
---@param setting boolean?
---@return boolean
function Targets.enabled(setting)
    return setting ~= false
end

return Targets
