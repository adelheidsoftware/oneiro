-- utils/builder_set.lua
-- Makes a StringBuilder, which is a list of pieces, hold only the text.

local function builderSet(builder, text)
    for i = #builder, 1, -1 do
        builder[i] = nil
    end
    builder[1] = text
end

return { builderSet = builderSet }
