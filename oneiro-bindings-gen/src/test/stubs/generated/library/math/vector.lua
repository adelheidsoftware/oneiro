---@meta

---Two numbers.
---@class Vector2
---@field x number The first.
---@field y number The second.
---@operator add(Vector2): Vector2
---@operator sub(Vector2): Vector2
---@operator mul(Vector2): Vector2
---@operator mul(number): Vector2
---@operator div(Vector2): Vector2
---@operator div(number): Vector2
Vector2 = {}

---A vector of two numbers.
---@overload fun(v: number): Vector2
---@param x number The first.
---@param y number The second.
---@return Vector2
function Vector2.new(x, y) end

---The length.
---@return number # The length.
function Vector2:Length() end
