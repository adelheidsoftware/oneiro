---@meta

---The skills.
---@enum SkillType
SkillType = {
    ATTACK = 0,
    DEFENCE = 1,
    INVENTION = 26,
}

---The name of one of the values, as `SkillType.FromID(SkillType.ATTACK)` is `"ATTACK"`.
---@param value integer
---@return string
---@diagnostic disable-next-line: inject-field
function SkillType.FromID(value) end

---@class Player
Player = {}

---Whether a character is logged in.
---@return boolean # True when Player data can be read.
function Player.IsAvailable() end

---A value unique to the character.
---@return Long? # The value, or nil while it has not arrived.
function Player.GetTrackingID() end

---A skill's level with boosts.
---@param skill SkillType The skill.
---@return integer # The level.
function Player.GetLevelGameplay(skill) end

---The character's entity.
---@return PlayerEntity # The entity.
function Player.GetEntity() end
