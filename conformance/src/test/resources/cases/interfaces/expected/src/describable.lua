-- Things that can say what they are, with a shared way of saying it.

local Describable = {}

-- How a description starts.
Describable.OPENING = "a"

-- The thing's label and what it is.
---@return string
function Describable:describe()
    return formatStr("{} {} {}", Describable.OPENING, self:label(), self:kind())
end

-- What kind of thing it is; a thing unless it says otherwise.
---@return string
function Describable:kind()
    return "thing"
end

-- The thing's description in capitals.
---@param thing Describable
---@return string
function Describable.shout(thing)
    return thing:describe():upper()
end

return Describable
