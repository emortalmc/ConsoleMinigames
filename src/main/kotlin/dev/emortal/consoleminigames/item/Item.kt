package dev.emortal.consoleminigames.item

import io.github.bloepiloepi.pvp.enums.Tool
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.item.ItemHideFlag
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.math.abs

class Item(val material: Material, val weight: Int, val itemCreate: (ItemStack.Builder) -> Unit = { }) {

    val rarerWeight = abs(weight - 16)

    val itemStack: ItemStack get() {

        return ItemStack.builder(material)
            .also { itemCreate.invoke(it) }
            .also {
                val tool = Tool.fromMaterial(material)

                if (tool != null) {
                    val damage = tool.attackDamage.toInt()

                    it.lore(
                        Component.text()
                            .append(Component.text("Deals ", NamedTextColor.GRAY))
                            .append(Component.text("❤".repeat(damage), NamedTextColor.RED))
                            .append(Component.text(" (${damage})", NamedTextColor.GRAY))
                            .build()
                            .decoration(TextDecoration.ITALIC, false)
                    )
                    it.meta {
                        it.hideFlag(ItemHideFlag.HIDE_ATTRIBUTES)
                    }
                }

            }
            .build()
    }

}