---@meta
-- Gauges, a library plugin the case depends on: bars that show a value.

---@class gauges
---@field version string The library's version.
gauges = {}

---@class gauges.Gauge
---@field value number The value it shows.
gauges.Gauge = {}

---@param label string
---@return gauges.Gauge
function gauges.Gauge.new(label) end

---@param value number
function gauges.Gauge:SetValue(value) end
