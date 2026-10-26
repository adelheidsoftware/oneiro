---@meta

-- A fixture of definition files laid out as an LLS-Addons repository is, for a library plugin seen as a global.

--- Gauges: bars that show a value between bounds, for a plugin's own windows.
---@class gauges
---@field version string The library's version, such as "1.0.0".
gauges = {}

--- A gauge: a bar in a parent component.
---@class gauges.Gauge
---@field Highlight fun(self: gauges.Gauge, colour: integer): gauges.Gauge Draws the bar in the colour, until the next value.
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

--- How a gauge's parts are drawn, which the options of each part extend.
---@class gauges.Style
---@field colour integer?

--- The options of a gauge's label.
---@class gauges.LabelOptions: gauges.Style
---@field text string

--- Labels the gauge with a text, or with a text in a colour.
---@param label string|gauges.LabelOptions
function gauges.Gauge:SetLabel(label) end

--- Shows a counter's value from now on, a type of another library.
---@param counter widgets.Counter
function gauges.Gauge:Follow(counter) end

--- A gauge that asks for a value, as a dialog.
---@class gauges.Prompt
gauges.Prompt = {}

--- Shows the prompt, whose answer is a gauge's value.
---@param parent ui.Component
---@return gauges.Gauge
function gauges.Prompt.new(parent) end
