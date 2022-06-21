package dev.emortal.consoleminigames.item

import net.minestom.server.inventory.Inventory
import net.minestom.server.item.ItemStack
import java.util.concurrent.ThreadLocalRandom

fun Inventory.addRandomly(itemStack: ItemStack) {
    var randomSlot: Int

    var validSlot = false
    while (!validSlot) {
        randomSlot = ThreadLocalRandom.current().nextInt(size)

        if (getItemStack(randomSlot) == ItemStack.AIR) {
            validSlot = true
            setItemStack(randomSlot, itemStack)
        }
    }
}