package dev.emortal.consoleminigames.game

import dev.emortal.consoleminigames.ConsoleMinigamesExtension
import dev.emortal.consoleminigames.game.BattlePlayerHelper.cleanup
import dev.emortal.consoleminigames.game.BattlePlayerHelper.kills
import dev.emortal.consoleminigames.item.Items
import dev.emortal.consoleminigames.item.addRandomly
import dev.emortal.immortal.config.GameOptions
import dev.emortal.immortal.game.GameManager
import dev.emortal.immortal.game.GameState
import dev.emortal.immortal.game.PvpGame
import dev.emortal.immortal.util.MinestomRunnable
import dev.emortal.immortal.util.armify
import io.github.bloepiloepi.pvp.PvpExtension
import io.github.bloepiloepi.pvp.damage.CustomDamageType
import io.github.bloepiloepi.pvp.damage.CustomEntityDamage
import io.github.bloepiloepi.pvp.events.*
import io.github.bloepiloepi.pvp.explosion.ExplosionListener
import io.github.bloepiloepi.pvp.explosion.PvpExplosionSupplier
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.ItemEntity
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.item.ItemDropEvent
import net.minestom.server.event.item.PickupItemEvent
import net.minestom.server.event.player.*
import net.minestom.server.instance.AnvilLoader
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockHandler.Dummy
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.BlockActionPacket
import net.minestom.server.sound.SoundEvent
import net.minestom.server.tag.Tag
import net.minestom.server.utils.PacketUtils
import net.minestom.server.utils.time.TimeUnit
import world.cepi.kstom.Manager
import world.cepi.kstom.event.listenOnly
import world.cepi.kstom.util.playSound
import world.cepi.kstom.util.roundToBlock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin


class BattleGame(gameOptions: GameOptions) : PvpGame(gameOptions) {

    companion object {
        val centerPos = Pos(268.5, 26.0, 12.5)

        val blockedItemStack = ItemStack.builder(Material.GRAY_STAINED_GLASS_PANE)
            .displayName(Component.empty())
            .build()
    }

    private val chests = ConsoleMinigamesExtension.cavernsChests
    private val openedChests = chests.toMutableList() // toMutableList makes sure we have a copy instead of modifying cavernsChests directly

    override var spawnPosition: Pos = Pos(268.5, 26.0, -0.5)

    private val playerChestMap = ConcurrentHashMap<Player, Point>()

    private var gameStarted = false
    private var playersInvulnerable = true

    init {
        eventNode.listenOnly<FinalDamageEvent> {
            val player = entity as? Player ?: return@listenOnly

            if (gameState != GameState.PLAYING || playersInvulnerable) {
                isCancelled = true
                return@listenOnly
            }

            if (damageType == CustomDamageType.FALL) {
                val blockUnder = instance.getBlock(player.position.sub(0.0, 1.0, 0.0))
                val blockIn = instance.getBlock(player.position)

                if (blockUnder == Block.SLIME_BLOCK && !player.isSneaking) {
                    isCancelled = true
                    return@listenOnly
                }
                if (blockIn.compare(Block.WATER)) {
                    isCancelled = true
                    return@listenOnly
                }
            }
        }

        eventNode.listenOnly<PlayerSpectateEvent> {
            isCancelled = true
        }

        eventNode.listenOnly<PlayerExhaustEvent> {
            if (gameState != GameState.PLAYING || playersInvulnerable) isCancelled = true

            this.amount = amount / 1.6f
        }

        eventNode.listenOnly<FinalAttackEvent> {
            if (gameState != GameState.PLAYING || playersInvulnerable) isCancelled = true
        }
    }

    override fun gameStarted() {

        ConsoleMinigamesExtension.cavernsChests.forEach {
            instance.setBlock(it, SingleChestHandler.create())
        }

        players.forEachIndexed { i, player ->
            player.teleport(getCircleSpawnPosition(i))
        }

        eventNode.listenOnly<PlayerMoveEvent> {
            if (!gameStarted) if (!this.player.position.samePoint(newPosition)) isCancelled = true
        }

        refillChests()

        val secondsToStart = 10
        val invulnerabilitySeconds = 15

        val bossBar = BossBar.bossBar(Component.text("Time to start: $secondsToStart seconds"), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS)
        showBossBar(bossBar)

        // Starting bossbar loop
        object : MinestomRunnable(coroutineScope = coroutineScope, repeat = Duration.ofMillis(50), iterations = secondsToStart * 20) {
            var lastNum = secondsToStart
            var progress = 1f

            override suspend fun run() {
                val currentIter = currentIteration.get()
                bossBar.progress(1f - (currentIter.toFloat() / iterations.toFloat()))

                if (currentIter % 20 == 0) {
                    bossBar.name(Component.text("Time to start: $lastNum seconds"))
                    if (lastNum <= 5) {
                        playSound(
                            Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_BASS, Sound.Source.MASTER, 2f, 1f),
                            Sound.Emitter.self()
                        )
                    }
                    lastNum--
                }
            }

            override fun cancelled() {
                registerEvents()

                playSound(Sound.sound(SoundEvent.ENTITY_ENDER_DRAGON_GROWL, Sound.Source.MASTER, 1f, 1.3f))

                bossBar.name(Component.text("Round start!"))
                bossBar.progress(1f)

                gameStarted = true

                object : MinestomRunnable(coroutineScope = coroutineScope, delay = Duration.ofSeconds(1), repeat = Duration.ofMillis(50), iterations = invulnerabilitySeconds * 20) {
                    var lastNum = invulnerabilitySeconds
                    var progress = 1f

                    override suspend fun run() {
                        val currentIter = currentIteration.get()
                        bossBar.progress(1f - (currentIter.toFloat() / iterations.toFloat()))

                        if (currentIter % 20 == 0) {
                            bossBar.name(Component.text("Invulnerability wears off in $lastNum seconds!"))
                            if (lastNum <= 3) {
                                playSound(Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_BASS, Sound.Source.MASTER, 2f, 1f))
                            }
                            lastNum--
                        }
                    }

                    override fun cancelled() {
                        playSound(Sound.sound(SoundEvent.ENTITY_ELDER_GUARDIAN_CURSE, Sound.Source.MASTER, 1f, 0.7f), Sound.Emitter.self())

                        bossBar.name(Component.text("Invulnerability has worn off! Fight!"))
                        sendMessage(
                            Component.text()
                                .append(Component.text("☠", NamedTextColor.RED))
                                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                                .append(Component.text("Invulnerability", NamedTextColor.WHITE))
                                .append(Component.text(" has worn off!", NamedTextColor.GRAY))
                                .append(Component.text(" Fight!", NamedTextColor.WHITE))
                        )

                        playersInvulnerable = false

                        instance.scheduler().buildTask {
                            hideBossBar(bossBar)
                        }.delay(Duration.ofSeconds(2)).schedule()
                    }
                }
            }
        }


        // Chest refill task
        val refillInterval = Duration.ofSeconds(75)
        object : MinestomRunnable(coroutineScope = coroutineScope, delay = refillInterval, repeat = refillInterval) {
            override suspend fun run() {
                if (gameState == GameState.ENDING) return

                refillChests()

                sendMessage(
                    Component.text()
                        .append(Component.text("★", NamedTextColor.YELLOW))
                        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Chests", NamedTextColor.WHITE))
                        .append(Component.text(" have been ", NamedTextColor.GRAY))
                        .append(Component.text("refilled", NamedTextColor.WHITE))
                        .append(Component.text("!", NamedTextColor.GRAY))
                )

                showTitle(
                    Title.title(
                        Component.empty(),
                        Component.text()
                            .append(Component.text("Chests ", NamedTextColor.YELLOW))
                            .append(Component.text("refilled!", NamedTextColor.YELLOW, TextDecoration.OBFUSCATED))
                            .build(),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(2), Duration.ofSeconds(1))
                    )
                )

                playSound(Sound.sound(SoundEvent.BLOCK_ENDER_CHEST_OPEN, Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())
                instance.scheduler().buildTask {
                    playSound(Sound.sound(SoundEvent.BLOCK_ENDER_CHEST_CLOSE, Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())
                }.delay(Duration.ofMillis(1250)).schedule()
                instance.scheduler().buildTask {
                    showTitle(
                        Title.title(
                            Component.empty(),
                            Component.text("Chests refilled!", NamedTextColor.YELLOW),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(500), Duration.ofMillis(500))
                        )
                    )
                }.delay(Duration.ofMillis(1600)).schedule()
            }
        }
    }

    private fun refillChests() {
        openedChests.forEach {
            val chest = instance.getBlock(it).handler() as SingleChestHandler

            chest.inventory.clear()
            repeat(7) {
                chest.inventory.addRandomly(Items.randomItem().itemStack)
            }
        }
        openedChests.clear()
    }


    var firstJoin = true
    override fun playerJoin(player: Player) {
        if (firstJoin) {
            firstJoin = false
            instance.enableAutoChunkLoad(false)
        }

        for (i in 9..40) { // just the inventory minus hotbar
            player.inventory.setItemStack(i, blockedItemStack)
        }
        PvpExtension.setLegacyAttack(player, true)
    }

    override fun playerLeave(player: Player) {

    }

    override fun registerEvents() {
        eventNode.listenOnly<PlayerTickEvent> {
            val insideBlock = instance.getBlock(player.position)

            if (insideBlock.compare(Block.WATER) && player.isOnFire) {
                player.isOnFire = false
            }
            if (insideBlock.compare(Block.FIRE)) {
                player.setFireDamagePeriod(Duration.ofSeconds(7))
            }
        }

        eventNode.listenOnly<ExplosionEvent> {
            this.affectedBlocks.clear()
        }
        eventNode.listenOnly<PlayerBlockPlaceEvent> {
            if (block.compare(Block.TNT)) {

                isCancelled = true
                ExplosionListener.primeTnt(instance, blockPosition, player, 50)
                player.itemInMainHand = if (player.itemInMainHand.amount() == 1) ItemStack.AIR else  player.itemInMainHand.withAmount(player.itemInMainHand.amount() - 1)

                return@listenOnly
            }
        }

        val itemOwnerTag = Tag.UUID("itemOwner")

        eventNode.listenOnly<PlayerEatEvent> {
            if (itemStack.material() == Material.POTION) {
                player.setItemInHand(hand, ItemStack.AIR)
            }
        }

        eventNode.listenOnly<ItemDropEvent> {
            if (itemStack.material() == blockedItemStack.material()) {
                isCancelled = true
                return@listenOnly
            }

            val itemEntity = ItemEntity(itemStack)
            itemEntity.setTag(itemOwnerTag, player.uuid)
            itemEntity.setPickupDelay(40, TimeUnit.SERVER_TICK)
            val velocity = player.position.direction().mul(6.0)
            itemEntity.velocity = velocity
            itemEntity.scheduleRemove(Duration.ofMinutes(3))
            itemEntity.setInstance(player.instance!!, player.position.add(0.0, 1.5, 0.0))
        }
        eventNode.listenOnly<PickupItemEvent> {
            val player = entity as? Player ?: return@listenOnly

            if (player.gameMode != GameMode.ADVENTURE) {
                isCancelled = true
                return@listenOnly
            }

            val itemOwner = itemEntity.getTag(itemOwnerTag)
            // Only let purposely dropped items be picked up by the owner, unless the owner has died
            if (itemOwner != player.uuid && Manager.connection.getPlayer(itemOwner)?.gameMode == GameMode.ADVENTURE) {
                isCancelled = true
                return@listenOnly
            }

            val couldAdd = player.inventory.addItemStack(itemStack)
            isCancelled = !couldAdd
        }

        eventNode.listenOnly<EntityPreDeathEvent> {
            if (entity !is Player) return@listenOnly

            val player = entity as Player
            this.isCancelled = true

            if (damageType is CustomEntityDamage) kill(player, (damageType as CustomEntityDamage).entity)
            else kill(player)

        }

        eventNode.listenOnly<PlayerBlockInteractEvent> {
            if (player.gameMode != GameMode.ADVENTURE) return@listenOnly
            if (block.compare(Block.CHEST)) {
                if (block.handler() is Dummy) {
                    player.sendMessage("Chest didn't have handler")
                    instance.setBlock(blockPosition, SingleChestHandler.create())
                }
                val singleChest = block.handler() as SingleChestHandler
                player.openInventory(singleChest.inventory)

                openedChests.add(blockPosition)

                playerChestMap[player] = blockPosition

                val playersInside = singleChest.playersInside.incrementAndGet()
                val packet = BlockActionPacket(blockPosition, 1, playersInside.toByte(), Block.CHEST)
                PacketUtils.sendGroupedPacket(instance.players, packet)

                if (playersInside == 1) {
                    instance.playSound(
                        Sound.sound(SoundEvent.BLOCK_CHEST_OPEN, Sound.Source.BLOCK, 1f, 1f),
                        blockPosition.add(0.5, 0.5, 0.5)
                    )
                }

            }
        }

        eventNode.listenOnly<InventoryPreClickEvent> {

            if (clickedItem.material() == blockedItemStack.material()) {
                isCancelled = true
                this.cursorItem = ItemStack.AIR
            }
        }

        eventNode.listenOnly<InventoryCloseEvent> {
            val openChest = playerChestMap[player] ?: return@listenOnly
            val handler = instance.getBlock(openChest).handler() as? SingleChestHandler ?: return@listenOnly

            playerChestMap.remove(player)

            val playersInside = handler.playersInside.decrementAndGet()
            val packet = BlockActionPacket(openChest, 1, playersInside.toByte(), Block.CHEST)
            PacketUtils.sendGroupedPacket(instance.players, packet)

            if (playersInside == 0) instance.playSound(
                Sound.sound(
                    SoundEvent.BLOCK_CHEST_CLOSE,
                    Sound.Source.BLOCK,
                    1f,
                    1f
                ), openChest.add(0.5, 0.5, 0.5)
            )
        }
    }

    override fun respawn(player: Player) {

    }

    override fun playerDied(player: Player, killer: Entity?) {
        player.showTitle(Title.title(Component.text("YOU DIED", NamedTextColor.RED, TextDecoration.BOLD), Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))))

        playSound(Sound.sound(SoundEvent.ENTITY_GUARDIAN_DEATH, Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())


        if (killer != null && killer is Player) {
            killer.kills++

            killer.playSound(Sound.sound(SoundEvent.BLOCK_ANVIL_LAND, Sound.Source.MASTER, 0.35f, 2f), Sound.Emitter.self())

            sendMessage(
                Component.text()
                    .append(Component.text("☠", NamedTextColor.RED))
                    .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(player.username, NamedTextColor.RED))
                    .append(Component.text(" was slain by ", NamedTextColor.GRAY))
                    .append(Component.text(killer.username, NamedTextColor.WHITE))
            )
        } else {
            sendMessage(
                Component.text()
                    .append(Component.text("☠", NamedTextColor.RED))
                    .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(player.username, NamedTextColor.RED))
                    .append(Component.text(" died", NamedTextColor.GRAY))
            )
        }

        val alivePlayers = players.filter { it.gameMode == GameMode.ADVENTURE }

        if (alivePlayers.size <= 1) {
            victory(alivePlayers)
        } else {
            sendMessage(
                Component.text()
                    .append(Component.text("${alivePlayers.size} players left", NamedTextColor.RED))
            )
        }



        val rand = ThreadLocalRandom.current()
        player.inventory.itemStacks.filter { it.material() != blockedItemStack.material() }.forEach {
            val angle = rand.nextDouble(PI * 2)
            val strength = rand.nextDouble(3.0, 6.0)
            val x = cos(angle) * strength
            val z = sin(angle) * strength

            val itemEntity = ItemEntity(it)
            itemEntity.setPickupDelay(500, TimeUnit.MILLISECOND)
            itemEntity.velocity = Vec(x, rand.nextDouble(3.0, 7.0), z)
            itemEntity.setInstance(player.instance!!, player.position.add(0.0, 1.5, 0.0))
        }

        player.inventory.clear()
    }

    private fun getCircleSpawnPosition(playerNum: Int): Pos {
        val angle = playerNum * ((2 * PI) / 8)
        val x = cos(angle) * 13
        val z = sin(angle) * 13

        var pos = centerPos.add(x, 0.0, z).roundToBlock().add(0.5, 0.0, 0.5)
        val angle1 = centerPos.sub(pos.x(), pos.y(), pos.z())

        pos = pos.withDirection(angle1).withPitch(0f)

        return pos
    }

    override fun gameDestroyed() {
        players.forEach {
            it.cleanup()
        }
    }

    override fun gameWon(winningPlayers: Collection<Player>) {
        val lastManStanding = winningPlayers.firstOrNull() ?: return
        val highestKiller = players.maxByOrNull { it.kills }

        val message = Component.text()
            .append(Component.text(" ${" ".repeat(25)}VICTORY", NamedTextColor.GOLD, TextDecoration.BOLD))
            .append(Component.text("\n\n Winner: ", NamedTextColor.GRAY))
            .append(Component.text(lastManStanding.username, NamedTextColor.GREEN))
            .also {
                if (highestKiller != null) {
                    it.append(Component.text("\n Highest killer: ", NamedTextColor.GRAY))
                    it.append(Component.text(highestKiller.username, NamedTextColor.YELLOW))
                }
            }

        message.append(Component.newline())

        players.sortedBy { it.kills }.reversed().take(5).forEach { plr ->
            message.append(
                Component.text()
                    .append(Component.newline())
                    .append(Component.space())
                    .append(Component.text(plr.username, NamedTextColor.GRAY))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(plr.kills, NamedTextColor.WHITE))
            )
        }

        sendMessage(message.armify())
    }

    override fun instanceCreate(): Instance {
        val newInstance = Manager.instance.createInstanceContainer()
        newInstance.chunkLoader = AnvilLoader("./battle-maps/Caverns")
        newInstance.timeUpdate = null
        newInstance.timeRate = 0
        newInstance.setTag(GameManager.doNotAutoUnloadChunkTag, true)
        newInstance.explosionSupplier = PvpExplosionSupplier.INSTANCE

        return newInstance
    }
}
