package dev.emortal.consoleminigames.game

import net.minestom.server.entity.Player
import net.minestom.server.tag.Tag

object BattlePlayerHelper {

    var Player.kills: Int
        get() = getTag(killsTag) ?: 0
        set(value) = setTag(killsTag, value)

    fun Player.cleanup() {
        removeTag(killsTag)
    }

    val killsTag = Tag.Integer("kills")

}