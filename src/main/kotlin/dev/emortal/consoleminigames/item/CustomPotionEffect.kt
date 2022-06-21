package dev.emortal.consoleminigames.item

import net.minestom.server.potion.CustomPotionEffect
import net.minestom.server.potion.PotionEffect

//fun potionItem(val potionEffect: PotionEffect, val amplifier: Byte, )

fun customPotionEffect(
    potionEffect: PotionEffect,
    amplifier: Byte = 0,
    duration: Int,
    showParticles: Boolean = true,
    showIcon: Boolean = true
) = CustomPotionEffect(potionEffect.id().toByte(), amplifier, duration, false, showParticles, showIcon)