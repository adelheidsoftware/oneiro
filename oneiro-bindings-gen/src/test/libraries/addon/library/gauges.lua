---@meta

-- A fixture of definition files laid out as an LLS-Addons repository is, for a library plugin seen as a global.

--- Gauges: bars that show a value between bounds, for a plugin's own windows.
---@class gauges
---@field version string The library's version, such as "1.0.0".
gauges = {}

--- A gauge: a bar in a parent component.
---@class gauges.Gauge
gauges.Gauge = {}

--- The options of a gauge.
---@class gauges.GaugeOptions
---@field width number
---@field height number
---@field colour integer?
---@field onChange fun(value: number)?

--- A gauge in `parent`.
---@param parent ui.Component
---@param options gauges.GaugeOptions
---@return gauges.Gauge
function gauges.Gauge.new(parent, options) end

--- Shows `value`, clamped to the gauge's bounds.
---@param value number
function gauges.Gauge:SetValue(value) end

function gauges.Gauge:Destroy() end
