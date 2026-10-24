-- src/tag.lua
-- Method references to a private method below them, whose parameter has the method's name.

local Tag = {}
Tag.__index = Tag

-- Declared ahead: used above their definitions below.
local label

function Tag.new(prefix)
    local self = setmetatable({}, Tag)
    self.prefix = prefix
    return self
end

function Tag:all(names)
    local list = {}
    for _, item in ipairs(names) do
        list[#list + 1] = label(self, item)
    end
    return list
end

function Tag:labeller()
    return function(label_)
        return label(self, label_)
    end
end

function label(self, label)
    return self.prefix .. label
end

return Tag
