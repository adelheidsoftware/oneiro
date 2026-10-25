-- src/panels.lua
-- Kotlin's scope functions, opened where they are called: let, also, apply, run and with.

-- The string without leading and trailing whitespace, as Kotlin's String.trim.
local function trim(s)
    return s:match("^%s*(.-)%s*$")
end

local Panels = {}

-- A running total.
local Tally = {}
Tally.__index = Tally
Panels.Tally = Tally

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

-- A label under the parent, placed and filled in.
---@param parent ui.ComponentCollection
---@param words string
---@return ui.Text
function Panels.label(parent, words)
    local text = ui.Text.new(parent)
    text.content = words
    text:SetPos(10, 20)
    return text
end

-- A label under the parent, kept in a local, then shown.
---@param parent ui.ComponentCollection
---@param words string
---@return ui.Text
function Panels.shown(parent, words)
    local label = ui.Text.new(parent)
    -- Where the label goes
    label:SetPos(10, 20)
    label.content = words
    label.hidden = false
    return label
end

-- Hides a label and moves it out of the way.
---@param label ui.Text
function Panels.hide(label)
    label.hidden = true
    label:SetPos(0, 0)
end

-- The name in capitals.
---@param name string
---@return string
function Panels.shout(name)
    local loud = name:upper()
    return loud
end

-- The length of the name without its outer spaces, or 0 for no name.
---@param name string?
---@return integer
function Panels.trimmedLength(name)
    return name and #trim(name) or 0
end

-- A new tally of the amount.
---@param amount integer
---@return Tally
function Panels.tallied(amount)
    local tally = Tally.new(0)
    tally:add(amount)
    return tally
end

-- Logs the tally's total, and gives the tally on.
---@param tally Tally
---@return Tally
function Panels.logged(tally)
    log(formatStr("total {}", tally.total))
    return tally
end

-- The total after adding both amounts to the tally.
---@param tally Tally
---@param first integer
---@param second integer
---@return integer
function Panels.total(tally, first, second)
    tally:add(first)
    tally:add(second)
    return tally.total
end

-- The total of a new tally of the amount, after adding one.
---@param amount integer
---@return integer
function Panels.fresh(amount)
    local total
    do
        local tally = Tally.new(amount)
        tally:add(1)
        total = tally.total
    end
    return total
end

-- A line about the tally.
---@param tally Tally
---@return string
function Panels.describe(tally)
    return formatStr("total {}", tally.total)
end

-- Logs each name of more than three letters, in capitals.
---@param names string[]
function Panels.logLong(names)
    for _, name in ipairs(names) do
        if #name > 3 then
            log(name:upper())
        end
    end
end

-- A line about the name's length.
---@param name string
---@return string
function Panels.nameLength(name)
    return formatStr("length {}", #name)
end

-- Logs what a tally of three comes to after adding two.
function Panels.demo()
    do
        local tally = Tally.new(3)
        tally:add(2)
        log(formatStr("total {}", tally.total))
    end
end

return Panels
