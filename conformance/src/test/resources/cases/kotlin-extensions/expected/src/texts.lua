-- src/texts.lua
-- Extension functions, which take their receiver first, as self.

-- A table whose pairs walks its keys in the order they were first added, as Kotlin's maps and sets do, but for its hash
-- maps and hash sets. It holds its entries itself; its metatable keeps their order.
local function orderedTable()
    local keys = {}
    local added = {}
    return setmetatable({}, {
        __newindex = function(t, key, value)
            if value == nil then
                return
            end
            if added[key] then
                -- A key removed and added again goes to the end, as in a LinkedHashMap
                for i = #keys, 1, -1 do
                    if keys[i] == key then
                        table.remove(keys, i)
                        break
                    end
                end
            end
            added[key] = true
            keys[#keys + 1] = key
            rawset(t, key, value)
        end,
        __pairs = function(t)
            local i = 0
            return function()
                repeat
                    i = i + 1
                    local key = keys[i]
                    -- A key removed since it was added is passed over
                    if key ~= nil and rawget(t, key) ~= nil then
                        return key, rawget(t, key)
                    end
                until key == nil
            end
        end,
    })
end

-- Adds each element of the list to the set, and gives the set.
local function addElements(set, list)
    for _, value in ipairs(list) do
        set[value] = true
    end
    return set
end

local Texts = {}

-- A running total, which extension functions add to.
local Tally = {}
Tally.__index = Tally
Texts.Tally = Tally

function Tally.new(total)
    local self = setmetatable({}, Tally)
    self.total = total
    return self
end

-- Adds the amount to the total.
---@param amount integer
function Tally:add(amount)
    self.total = self.total + amount
end

local HERBS = addElements(orderedTable(), { "guam", "ranarr" })

-- The text in capitals, as if shouted.
---@param self string
---@return string
function Texts.shout(self)
    return formatStr("{}!", self:upper())
end

-- The text shouted twice.
---@param self string
---@return string
function Texts.shoutTwice(self)
    return formatStr("{} {}", Texts.shout(self), Texts.shout(self))
end

-- Whether the text is a herb's name.
---@param self string
---@return boolean
local function isHerb(self)
    return HERBS[self] ~= nil
end

-- The herbs among the names.
---@param names string[]
---@return string[]
function Texts.herbs(names)
    local list = {}
    for _, it in ipairs(names) do
        if isHerb(it) then
            list[#list + 1] = it
        end
    end
    return list
end

-- Adds the amount to the tally twice.
---@param self Tally
---@param amount integer
function Texts.addTwice(self, amount)
    self:add(amount)
    self:add(amount)
end

-- Twice the tally's total.
---@param self Tally
---@return integer
function Texts.doubled(self)
    return self.total * 2
end

-- The text shouted, or null for no text.
---@param text string?
---@return string?
function Texts.loud(text)
    return text and Texts.shout(text)
end

-- The letters of the text after the first.
---@param self string
---@return integer
function Texts.rest(self)
    return #self - 1
end

-- The numbers added up.
---@param self integer[]
---@return integer
function Texts.added(self)
    local sum = 0
    for _, item in ipairs(self) do
        sum = sum + item
    end
    return sum
end

-- The herbs among the names, in capitals.
---@param self string[]
---@return string[]
function Texts.loudHerbs(self)
    local list = {}
    for _, it in ipairs(self) do
        if isHerb(it) then
            list[#list + 1] = it:upper()
        end
    end
    return list
end

-- The letters of all the names.
---@param self string[]
---@return integer
function Texts.totalLength(self)
    local letters = 0
    for _, it in ipairs(self) do
        letters = letters + #it
    end
    return letters
end

-- Adds the name, after "start" when the list is empty.
---@param self string[]
---@param name string
function Texts.record(self, name)
    if #self == 0 then
        self[#self + 1] = "start"
    end
    self[#self + 1] = name
end

return Texts
