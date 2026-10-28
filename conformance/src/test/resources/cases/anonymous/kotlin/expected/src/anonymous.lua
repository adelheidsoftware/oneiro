-- src/anonymous.lua
-- Object expressions: functions where Kotlin means one, objects otherwise.

local withMembers = require("utils/with_members").withMembers

local Anonymous = {}

-- A shape whose area each kind gives.
local Shape = {}
Shape.__index = Shape
Anonymous.Shape = Shape

function Shape.new(name)
    local self = setmetatable({}, Shape)
    self.name = name
    return self
end

function Shape:describe()
    return formatStr("{} of {}", self.name, self:area())
end

-- Sorts the words longest first, with an object expression as the comparator.
---@param words string[]
---@return string[]
function Anonymous.longestFirst(words)
    local sorted = table.move(words, 1, #words, 1, {})
    table.sort(sorted, function(a, b)
        return #b - #a < 0
    end)
    return sorted
end

-- Runs a task the given number of times, which counts in the list.
---@param times integer
---@param counts integer[]
function Anonymous.repeated(times, counts)
    local task = function()
        counts[#counts + 1] = #counts
    end
    for i = 1, times do
        task()
    end
end

-- A listener that answers with the word it heard, and how many it has heard.
---@param prefix string
---@return Listener
function Anonymous.echo(prefix)
    return {
        heardCount = 0,
        heard = function(self, word)
            self.heardCount = self.heardCount + 1
            return formatStr("{}{} ({})", prefix, word, self.heardCount)
        end,
        name = function(self)
            return "echo"
        end,
    }
end

-- The words, each with its length after the separator, through a class of this function's own.
---@param words string[]
---@param separator string
---@return string[]
function Anonymous.counted(words, separator)
    local Count = {}
    Count.__index = Count
    function Count.new(word)
        local self = setmetatable({}, Count)
        self.word = word
        return self
    end
    function Count:text()
        return formatStr("{}{}{}", self.word, separator, #self.word)
    end
    local texts = {}
    for _, word in ipairs(words) do
        texts[#texts + 1] = Count.new(word):text()
    end
    return texts
end

-- A square of side 1, made on the spot.
---@return Shape
function Anonymous.unitSquare()
    return withMembers(Shape.new("square"), {
        area = function(self)
            return 1.0
        end,
    })
end

return Anonymous
