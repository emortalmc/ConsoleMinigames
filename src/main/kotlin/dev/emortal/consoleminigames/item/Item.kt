package dev.emortal.consoleminigames.item

import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

class Item(val material: Material, val weight: Int, val itemCreate: (ItemStack.Builder) -> Unit = { }) {

    val itemStack = ItemStack.builder(material)
        .also { itemCreate.invoke(it) }
        .build()

}