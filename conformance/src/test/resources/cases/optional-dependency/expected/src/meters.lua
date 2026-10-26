-- src/meters.lua
-- An optional library plugin, used where a check that it is loaded reaches.

local Meters = {}

function Meters.show(value)
    if gauges then
        local gauge = gauges.Gauge.new("health")
        gauge:SetValue(value)
    end
end

function Meters.version()
    if not gauges then
        return "none"
    end
    return gauges.version
end

function Meters.present()
    return gauges ~= nil
end

function Meters.label(wanted)
    return wanted and gauges and formatStr("v{}", gauges.version) or "-"
end

function Meters.isGauges(identifier)
    return identifier == "gauges"
end

return Meters
