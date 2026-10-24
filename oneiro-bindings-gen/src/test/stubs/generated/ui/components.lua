---@meta

---@class ui
ui = {}

---A user interface component.
---@class ui.Component
---@field hidden Bool Whether it is hidden.
---@field visible Bool (Read-only) Whether it shows.
ui.Component = {}

---Moves it.
---@param x Int The x.
---@param y Int The y.
function ui.Component:SetPos(x, y) end

---Subscribes a listener for a hook, optionally under an ID.
---@overload fun(self: Component, hook: ui.Hook, id: String, callback: fun(...):Bool?)
---@param hook ui.Hook The hook to listen for.
---@param callback fun(...):Bool? The callback, with parameters varying by hook.
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
---@field content String The text.
ui.Text = {}

---A text component under the parent.
---@param parent ui.ComponentCollection The parent.
---@return ui.Text
function ui.Text.new(parent) end

---The interfaces.
---@class ui.Interfaces
ui.Interfaces = {}

---A component of an interface.
---@param interfaceID Int The interface.
---@param componentID Int The component.
---@return ui.Component? # The component, or nil.
function ui.Interfaces:GetComponent(interfaceID, componentID) end
