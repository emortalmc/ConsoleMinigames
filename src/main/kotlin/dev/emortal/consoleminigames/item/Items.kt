package dev.emortal.consoleminigames.item

import net.minestom.server.instance.block.Block
import net.minestom.server.item.Enchantment
import net.minestom.server.item.ItemHideFlag
import net.minestom.server.item.Material
import net.minestom.server.item.metadata.PotionMeta
import net.minestom.server.potion.PotionEffect
import net.minestom.server.potion.PotionType
import java.util.concurrent.ThreadLocalRandom

object Items {

    val common = 30
    val lesscommon = 25
    val uncommon = 20
    val rare = 15
    val veryrare = 10
    val epic = 5
    val legendary = 1

    val random = ThreadLocalRandom.current()

    fun randomItem(): Item {
        val possibleItems = items.filter { it.weight != 0 }
        val totalWeight = possibleItems.sumOf { it.weight }

        var idx = 0

        var r = ThreadLocalRandom.current().nextInt(totalWeight)
        while (idx < possibleItems.size - 1) {
            r -= possibleItems[idx].weight
            if (r <= 0.0) break
            ++idx
        }

        return possibleItems[idx]
    }

    // https://minecraft.fandom.com/wiki/Mini_games#Loot
    val items = listOf(
        // Consumables
        Item(Material.APPLE, common),
        Item(Material.COOKED_PORKCHOP, common),
        Item(Material.GOLDEN_APPLE, veryrare),

        // Weapons
        Item(Material.BOW, rare),
        Item(Material.ARROW, lesscommon) { it.amount(random.nextInt(2, 6)) },
        Item(Material.TIPPED_ARROW, rare) {
            it.meta(PotionMeta::class.java) {
                it.effects(listOf(customPotionEffect(PotionEffect.POISON, 0, 5 * 20)))
                it.potionType(PotionType.POISON)
            }
        },

        // Swords
        Item(Material.WOODEN_SWORD, uncommon),
        Item(Material.STONE_SWORD, rare),
        Item(Material.DIAMOND_SWORD, epic) { it.meta { it.damage(781) } }, // half durability

        // Axes
        Item(Material.WOODEN_AXE, rare),
        Item(Material.STONE_AXE, veryrare),
        Item(Material.IRON_AXE, epic),

        // Pickaxes
        Item(Material.WOODEN_PICKAXE, lesscommon),
        Item(Material.STONE_PICKAXE, uncommon),
        Item(Material.IRON_PICKAXE, rare),
        Item(Material.DIAMOND_PICKAXE, veryrare),

        // Shovels
        Item(Material.WOODEN_SHOVEL, lesscommon),
        Item(Material.STONE_SHOVEL, uncommon),
        Item(Material.IRON_SHOVEL, rare),
        Item(Material.DIAMOND_SHOVEL, veryrare),

        // Misc
        Item(Material.WOODEN_HOE, rare) {
            it.meta {
                it.enchantment(Enchantment.FIRE_ASPECT, 1)
                it.enchantment(Enchantment.KNOCKBACK, 2)
            }
        },
        //Item(Material.SHEARS, lesscommon),
        Item(Material.STICK, uncommon) {
            it.meta {
                it.enchantment(Enchantment.SHARPNESS, 2)
            }
        },
        //Item(Material.FISHING_ROD, rare),
        Item(Material.TNT, lesscommon) {
            it.amount(random.nextInt(1, 3))
            it.meta {
                it.canPlaceOn(Block.values().toMutableSet())
                it.hideFlag(ItemHideFlag.HIDE_PLACED_ON)
            }
        },

        // Potions
        Item(Material.TOTEM_OF_UNDYING, legendary),
        Item(Material.POTION, rare) {
            it.meta(PotionMeta::class.java) {
                it.potionType(PotionType.STRONG_HEALING)
            }
        },
//        Item(Material.LINGERING_POTION, rare) {
//            it.meta(PotionMeta::class.java) {
//                it.potionType(PotionType.REGENERATION)
//            }
//        },
        Item(Material.POTION, rare) {
            it.meta(PotionMeta::class.java) {
                it.potionType(PotionType.FIRE_RESISTANCE)
            }
        },
        Item(Material.POTION, rare) {
            it.meta(PotionMeta::class.java) {
                it.potionType(PotionType.INVISIBILITY)
                it.effects(listOf(customPotionEffect(PotionEffect.INVISIBILITY, 0, 60 * 20)))
            }
        },
        Item(Material.POTION, rare) {
            it.meta(PotionMeta::class.java) {
                it.potionType(PotionType.STRENGTH)
                it.hideFlag(ItemHideFlag.HIDE_POTION_EFFECTS)
                it.effects(listOf(customPotionEffect(PotionEffect.STRENGTH, 0, 25 * 20)))
            }
        },
        Item(Material.POTION, rare) {
            it.meta(PotionMeta::class.java) {
                it.potionType(PotionType.REGENERATION)
                it.hideFlag(ItemHideFlag.HIDE_POTION_EFFECTS)
            }
        },
        Item(Material.SPLASH_POTION, rare) {
            it.meta(PotionMeta::class.java) {
                it.potionType(PotionType.STRONG_HARMING)
                it.hideFlag(ItemHideFlag.HIDE_POTION_EFFECTS)
            }
        },
//        Item(Material.LINGERING_POTION, rare) {
//            it.meta(PotionMeta::class.java) {
//                it.potionType(PotionType.STRONG_HARMING)
//            }
//        },
        Item(Material.SPLASH_POTION, rare) {
            it.meta(PotionMeta::class.java) {
                it.potionType(PotionType.POISON)
                it.effects(listOf(customPotionEffect(PotionEffect.POISON, 1, 15 * 20)))
                it.hideFlag(ItemHideFlag.HIDE_POTION_EFFECTS)
            }
        },
        Item(Material.SPLASH_POTION, rare) {
            it.meta(PotionMeta::class.java) {
                it.effects(listOf(customPotionEffect(PotionEffect.SLOWNESS, 0, 45 * 20)))
                it.potionType(PotionType.SLOWNESS)
                it.hideFlag(ItemHideFlag.HIDE_POTION_EFFECTS)
            }
        },
        Item(Material.SPLASH_POTION, rare) {
            it.meta(PotionMeta::class.java) {
                it.effects(listOf(customPotionEffect(PotionEffect.WEAKNESS, 0, 45 * 20)))
                it.potionType(PotionType.WEAKNESS)
                it.hideFlag(ItemHideFlag.HIDE_POTION_EFFECTS)
            }
        },

        // Armour
        // Helmets
        Item(Material.LEATHER_HELMET, lesscommon),
        Item(Material.GOLDEN_HELMET, uncommon),
        Item(Material.CHAINMAIL_HELMET, rare),
        Item(Material.IRON_HELMET, veryrare),
        Item(Material.DIAMOND_HELMET, legendary),

        // Chestplates
        Item(Material.LEATHER_CHESTPLATE, lesscommon),
        Item(Material.GOLDEN_CHESTPLATE, uncommon),
        Item(Material.CHAINMAIL_CHESTPLATE, rare),
        Item(Material.IRON_CHESTPLATE, veryrare),
        Item(Material.DIAMOND_CHESTPLATE, legendary),

        // Leggings
        Item(Material.LEATHER_LEGGINGS, lesscommon),
        Item(Material.GOLDEN_LEGGINGS, uncommon),
        Item(Material.CHAINMAIL_LEGGINGS, rare),
        Item(Material.IRON_LEGGINGS, veryrare),
        Item(Material.DIAMOND_LEGGINGS, legendary),

        // Boots
        Item(Material.LEATHER_BOOTS, lesscommon),
        Item(Material.GOLDEN_BOOTS, uncommon),
        Item(Material.CHAINMAIL_BOOTS, rare),
        Item(Material.IRON_BOOTS, veryrare),
        Item(Material.DIAMOND_BOOTS, legendary),
    )

}