---@meta

---@class ui
ui = {}

---A user interface component.
---@class ui.Component
---@field hidden boolean Whether it is hidden.
---@field visible boolean (Read-only) Whether it shows.
ui.Component = {}

---Moves it.
---@param x integer The x.
---@param y integer The y.
function ui.Component:SetPos(x, y) end

---Subscribes a listener for a hook, optionally under an ID.
---@overload fun(self: Component, hook: ui.Hook, id: string, callback: fun(...):boolean?)
---@param hook ui.Hook The hook to listen for.
---@param callback fun(...):boolean? The callback, with parameters varying by hook.
function ui.Component:Subscribe(hook, callback) end

---Removes the listener for the hook.
---@param hook ui.Hook The hook.
function ui.Component:Unsubscribe(hook) end

---The hooks a component fires.
---@enum ui.Hook
ui.Hook = {
    ONBUTTONPRESSED = "ONBUTTONPRESSED", -- Triggered when the user presses this button.
    ONSELECTIONCHANGED = "ONSELECTIONCHANGED", -- Triggered when an entry's selected status changes.
    ONCLICK = "ONCLICK", -- Triggered when the user clicks this component.
    ONTIMER = "ONTIMER", -- Triggered once per logic tick.
}

---A component with children.
---@class ui.ComponentCollection : ui.Component
ui.ComponentCollection = {}

---Text on the screen.
---@class ui.Text : ui.Component
---@field content string The text.
ui.Text = {}

---A text component under the parent.
---@param parent ui.ComponentCollection The parent.
---@return ui.Text
function ui.Text.new(parent) end

---The interfaces.
---@class ui.Interfaces
ui.Interfaces = {}

---A component of an interface.
---@param interfaceID integer The interface.
---@param componentID integer The component.
---@return ui.Component? # The component, or nil.
function ui.Interfaces:GetComponent(interfaceID, componentID) end
