---@meta

---Two numbers.
---@class Vector2
---@field x Float The first.
---@field y Float The second.
Vector2 = {}

---A vector of two numbers.
---@overload fun(v: Float): Vector2
---@param x Float The first.
---@param y Float The second.
---@return Vector2
function Vector2.new(x, y) end

---The length.
---@return Float # The length.
function Vector2:Length() end
