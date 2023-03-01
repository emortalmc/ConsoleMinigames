package dev.emortal.consoleminigames.game

import dev.emortal.consoleminigames.ConsoleMinigamesMain
import dev.emortal.consoleminigames.game.BattlePlayerHelper.cleanup
import dev.emortal.consoleminigames.game.BattlePlayerHelper.kills
import dev.emortal.consoleminigames.item.Items
import dev.emortal.consoleminigames.item.addRandomly
import dev.emortal.immortal.game.GameManager
import dev.emortal.immortal.game.GameManager.joinGameOrNew
import dev.emortal.immortal.game.GameState
import dev.emortal.immortal.game.PvpGame
import dev.emortal.immortal.util.armify
import dev.emortal.immortal.util.parsed
import dev.emortal.immortal.util.roundToBlock
import dev.emortal.immortal.util.toFuture
import io.github.bloepiloepi.pvp.PvpExtension
import io.github.bloepiloepi.pvp.damage.CustomDamageType
import io.github.bloepiloepi.pvp.damage.CustomEntityDamage
import io.github.bloepiloepi.pvp.entity.EntityUtils
import io.github.bloepiloepi.pvp.events.*
import io.github.bloepiloepi.pvp.events.PlayerSpectateEvent
import io.github.bloepiloepi.pvp.explosion.ExplosionListener
import io.github.bloepiloepi.pvp.explosion.PvpExplosionSupplier
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.*
import net.minestom.server.event.EventNode
import net.minestom.server.event.entity.EntityTickEvent
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.item.ItemDropEvent
import net.minestom.server.event.item.PickupItemEvent
import net.minestom.server.event.player.*
import net.minestom.server.event.trait.InstanceEvent
import net.minestom.server.instance.AnvilLoader
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockHandler.Dummy
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.BlockActionPacket
import net.minestom.server.potion.PotionEffect
import net.minestom.server.scoreboard.Sidebar
import net.minestom.server.sound.SoundEvent
import net.minestom.server.timer.TaskSchedule
import net.minestom.server.utils.PacketUtils
import net.minestom.server.utils.time.TimeUnit
import org.slf4j.LoggerFactory
import world.cepi.particle.Particle
import world.cepi.particle.ParticleType
import world.cepi.particle.data.OffsetAndSpeed
import world.cepi.particle.extra.Dust
import world.cepi.particle.showParticle
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import java.util.stream.Collectors
import kotlin.io.path.nameWithoutExtension
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val LOGGER = LoggerFactory.getLogger(BattleGame::class.java)

class BattleGame(val map: String? = null) : PvpGame() {
    companion object {
        val blockedItemStack = ItemStack.builder(Material.BARRIER)
            .displayName(Component.empty())
            .build()
    }

    override val maxPlayers: Int = 8
    override val minPlayers: Int = 2
    override val countdownSeconds: Int = 0
    override val canJoinDuringGame: Boolean = false
    override val showScoreboard: Boolean = true
    override val showsJoinLeaveMessages: Boolean = true
    override val allowsSpectators: Boolean = true


    private lateinit var centerPos: Pos
    private var circleSize: Double = 0.0

    private val chests = CopyOnWriteArraySet<Point>()
    //private val openedChests = chests.toMutableSet() // toMutableList makes sure we have a copy instead of modifying cavernsChests directly
    private val unopenedRefilledChests = CopyOnWriteArraySet<Point>()

    var gameCenter: Pos? = null
    //override var spawnPosition: Pos = Pos(268.5, 26.0, -0.5)

    private val playerChestMap = ConcurrentHashMap<Player, Point>()

    private var gameStarted = false
    private var playersInvulnerable = true
    private var borderActive = false

    override fun gameCreated() {
        val eventNode = instance!!.eventNode()

        eventNode.addListener(InventoryPreClickEvent::class.java) { e ->
            if (e.clickedItem.material() == blockedItemStack.material()) {
                e.isCancelled = true
                e.cursorItem = ItemStack.AIR
            }
        }

        eventNode.addListener(FinalDamageEvent::class.java) { e ->
            val player = e.entity as? Player ?: return@addListener

            if (gameState != GameState.PLAYING || playersInvulnerable) {
                e.isCancelled = true
                return@addListener
            }

            if (e.damageType == CustomDamageType.FALL) {
                val blockUnder = e.instance.getBlock(player.position.sub(0.0, 1.0, 0.0))
                val blockIn = e.instance.getBlock(player.position)

                if (blockUnder == Block.SLIME_BLOCK && !player.isSneaking) {
                    e.isCancelled = true
                    return@addListener
                }
                if (blockIn.compare(Block.WATER)) {
                    e.isCancelled = true
                    return@addListener
                }
            }
        }

        eventNode.addListener(PlayerSpectateEvent::class.java) { e ->
            e.isCancelled = true
        }

        eventNode.addListener(PlayerExhaustEvent::class.java) { e ->
            if (gameState != GameState.PLAYING || playersInvulnerable) e.isCancelled = true

            e.amount = e.amount / 1.6f
        }

        eventNode.addListener(FinalAttackEvent::class.java) { e ->
            if (gameState != GameState.PLAYING || playersInvulnerable) e.isCancelled = true
        }

        LOGGER.info("Registering legacyEvents")
        eventNode.addChild(PvpExtension.legacyEvents())
    }

    var bossBar: BossBar? = null

    private val spawnPositionIndex = AtomicInteger(0)
    override fun getSpawnPosition(player: Player, spectator: Boolean): Pos {
        return if (!spectator) {
            getCircleSpawnPosition(spawnPositionIndex.getAndIncrement())
        } else {
            players.random().position
        }
    }

    override fun gameStarted() {
        instance!!.eventNode().addListener(PlayerMoveEvent::class.java) { e ->
            if (!gameStarted)
                if (!e.player.position.samePoint(e.newPosition))
                    e.isCancelled = true
        }

        var secondsToStart = 10
        var invulnerabilitySeconds = 15

        val secsUntilShowdown = players.size * 1.5 * 60L

        bossBar = BossBar.bossBar(Component.text("Time to start: $secondsToStart seconds"), 1f, BossBar.Color.PINK, BossBar.Overlay.PROGRESS)
        showBossBar(bossBar!!)

        scoreboard?.createLine(
            Sidebar.ScoreboardLine(
                "playersLeft",
                Component.text()
                    .append(Component.text("Players: ", NamedTextColor.GRAY))
                    .append(Component.text(players.size, NamedTextColor.RED))
                    .build(),
                1
            )
        )

        // Starting bossbar loop
        instance!!.scheduler().submitTask(object : Supplier<TaskSchedule> {
            var lastNum = secondsToStart

            override fun get(): TaskSchedule {
                if (lastNum == 0) {
                    playSound(Sound.sound(Key.key("battle.countdown.beginover"), Sound.Source.MASTER, 1f, 1f))

                    val rand = ThreadLocalRandom.current()
                    playSound(Sound.sound(Key.key("battle.music.battlemode${rand.nextInt(1, 5)}"), Sound.Source.MASTER, 0.5f, 1f))

                    bossBar!!.name(Component.text("Round start!"))

                    // Showdown timer
                    instance!!.scheduler().submitTask(object : Supplier<TaskSchedule> {
                        var iter = secsUntilShowdown.toInt()

                        override fun get(): TaskSchedule {
                            if (iter == 0) {
                                showdown()
                                return TaskSchedule.stop()
                            }

                            scoreboard?.updateLineContent(
                                "infoLine",
                                Component.text()
                                    .append(Component.text("Showdown in ", TextColor.color(59, 128, 59)))
                                    .append(Component.text(iter.parsed(), NamedTextColor.GREEN))
                                    .build()
                            )
                            iter--

                            return TaskSchedule.seconds(1)
                        }
                    })

                    gameStarted = true

                    // Invincibility timer
                    instance!!.scheduler().submitTask(object : Supplier<TaskSchedule> {
                        var iter = invulnerabilitySeconds

                        override fun get(): TaskSchedule {
                            if (iter == 0) {
                                playSound(Sound.sound(Key.key("battle.countdown.invulover"), Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())

                                bossBar!!.name(Component.text("Invulnerability has worn off! Fight!"))

                                playersInvulnerable = false

                                instance!!.scheduler().buildTask {
                                    hideBossBar(bossBar!!)
                                }.delay(Duration.ofSeconds(2)).schedule()

                                return TaskSchedule.stop()
                            }

                            bossBar!!.name(Component.text("Invulnerability wears off in $iter seconds!"))
                            if (iter <= 5) {
                                playSound(Sound.sound(Key.key("battle.countdown.begin"), Sound.Source.MASTER, 1f, 1f))
                            }
                            iter--

                            return TaskSchedule.seconds(1)
                        }
                    })

                }

                bossBar!!.name(Component.text("Time to start: $lastNum seconds"))
                if (lastNum <= 10) {
                    playSound(Sound.sound(Key.key("battle.countdown.begin"), Sound.Source.MASTER, 1f, 1f))
                }
                lastNum--

                return TaskSchedule.seconds(1)
            }
        })

        // Chest refill task
        val refillInterval = TaskSchedule.seconds(70)

        instance!!.scheduler().buildTask {
            if (gameState == GameState.ENDING) return@buildTask

            val chestsRefilled = refillChests()
            if (chestsRefilled == 0) return@buildTask

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

            playSound(Sound.sound(Key.key("battle.refill.open"), Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())
            instance!!.scheduler().buildTask {
                playSound(Sound.sound(Key.key("battle.refill.close"), Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())
            }.delay(Duration.ofMillis(1250)).schedule()
            instance!!.scheduler().buildTask {
                showTitle(
                    Title.title(
                        Component.empty(),
                        Component.text("Chests refilled!", NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(500), Duration.ofMillis(500))
                    )
                )
            }.delay(Duration.ofMillis(1600)).schedule()
        }.repeat(refillInterval).delay(refillInterval).schedule()

        // Refill chest particle task
        instance!!.scheduler().buildTask(object : Runnable {
            var i = 0.0

            override fun run() {
                if (i > 2 * PI) i = i % 2 * PI

                unopenedRefilledChests.forEach {
                    showParticle(
                        Particle.particle(
                            type = ParticleType.DUST,
                            extraData = Dust(1f, 1f, 0f, 0.75f),
                            count = 0,
                            data = OffsetAndSpeed(0f, 0.25f, 0f, 0.1f)
                        ), Vec.fromPoint(it).add(0.5 + (sin(i) * 0.5), 1.0, 0.5 + (cos(i) * 0.5))
                    )
                }

                i++
            }
        }).repeat(TaskSchedule.nextTick()).delay(refillInterval).schedule()

    }

    private fun refillChests(): Int {
        val rand = ThreadLocalRandom.current()

        val chestsRefilled = rand.nextInt(4, 6).coerceAtMost(chests.size)

        chests.filter { !unopenedRefilledChests.contains(it) }.shuffled().take(chestsRefilled).forEach {
            val chest = instance!!.getBlock(it).handler() as SingleChestHandler
            val isRarerChest = instance!!.getBlock(it.add(0.0, 1.0, 0.0)).compare(Block.STRUCTURE_VOID)

            unopenedRefilledChests.add(it)

            chest.inventory.clear()
            repeat(7) {
                chest.inventory.addRandomly(Items.randomItem(isRarerChest).itemStack)
            }
        }

        return chestsRefilled
    }


    override fun playerJoin(player: Player) {
        for (i in 9..40) { // just the inventory minus hotbar
            player.inventory.setItemStack(i, blockedItemStack)
        }
        PvpExtension.setLegacyAttack(player, true)
    }

    override fun playerLeave(player: Player) {
        val alivePlayers = players.filter { it.gameMode == GameMode.SURVIVAL }
        if (alivePlayers.size == 1) {
            if (gameState != GameState.PLAYING) return

            victory(alivePlayers.first())
        }
    }

    @Suppress("UnstableApiUsage")
    override fun registerEvents(eventNode: EventNode<InstanceEvent>) {

        eventNode.addListener(EntityTickEvent::class.java) { e ->
            if (e.entity.entityType == EntityType.ITEM && !e.entity.isOnFire) {
                val block = e.instance.getBlock(e.entity.position)
                if (block.compare(Block.LAVA) || block.compare(Block.FIRE)) {
                    e.entity.isOnFire = true
                    e.entity.scheduler().buildTask {
                        e.instance.playSound(Sound.sound(SoundEvent.ENTITY_GENERIC_BURN, Sound.Source.MASTER, 0.7f, 1f), e.entity.position)
                        e.entity.remove()
                    }.delay(Duration.ofMillis(500)).schedule()
                }
            }
        }

        eventNode.addListener(PlayerTickEvent::class.java) { e ->
//            val insideBlock = instance.getBlock(player.position)
            val player = e.player

            if (borderActive && player.gameMode == GameMode.ADVENTURE) {
                val point = player.position
                val radius: Double = (e.instance.worldBorder.diameter / 2.0) + 1.5
                val checkX = point.x() <= e.instance.worldBorder.centerX + radius && point.x() >= e.instance.worldBorder.centerX - radius
                val checkZ = point.z() <= e.instance.worldBorder.centerZ + radius && point.z() >= e.instance.worldBorder.centerZ - radius

                if (!checkX || !checkZ) {
                    kill(player)
                }
            }

            val blocksInHitbox = pointsBetween(player.boundingBox.relativeStart().add(player.position), player.boundingBox.relativeEnd().add(player.position))

            if (blocksInHitbox.any { e.instance.getBlock(it).compare(Block.WATER) }) {
                player.isOnFire = false
            }
            if (blocksInHitbox.any { e.instance.getBlock(it).compare(Block.FIRE) }) {
                EntityUtils.setFireForDuration(player, Duration.ofSeconds(6))

                if (player.aliveTicks % 10L == 0L) {
                    if (player.activeEffects.any { it.potion.effect == PotionEffect.FIRE_RESISTANCE }) return@addListener
                    player.damage(CustomDamageType.IN_FIRE, 1.0f)
                }
            }
            if (blocksInHitbox.any { e.instance.getBlock(it).compare(Block.LAVA) }) {
                EntityUtils.setFireForDuration(player, Duration.ofSeconds(12))

                if (player.aliveTicks % 10L == 0L) {
                    if (player.activeEffects.any { it.potion.effect == PotionEffect.FIRE_RESISTANCE }) return@addListener
                    player.damage(CustomDamageType.LAVA, 4.0f)
                }
            }
        }

        eventNode.addListener(ExplosionEvent::class.java) { e ->
            e.affectedBlocks.clear()
        }
        eventNode.addListener(PlayerBlockPlaceEvent::class.java) { e ->
            if (e.block.compare(Block.TNT)) {
                e.isCancelled = true
                ExplosionListener.primeTnt(instance, e.blockPosition, e.player, 40)
                e.player.setItemInHand(e.hand, if (e.player.itemInMainHand.amount() == 1) ItemStack.AIR else e.player.itemInMainHand.withAmount(e.player.itemInMainHand.amount() - 1))

                return@addListener
            }
        }

        eventNode.addListener(PlayerEatEvent::class.java) { e ->
            if (e.itemStack.material() == Material.POTION) {
                e.player.setItemInHand(e.hand, ItemStack.AIR)
            }
        }

        eventNode.addListener(ItemDropEvent::class.java) { e ->
            if (e.itemStack.material() == blockedItemStack.material()) {
                e.isCancelled = true
                return@addListener
            }

            val itemEntity = ItemEntity(e.itemStack)
            itemEntity.setPickupDelay(40, TimeUnit.SERVER_TICK)
            val velocity = e.player.position.direction().mul(6.0)
            itemEntity.velocity = velocity
            itemEntity.scheduleRemove(Duration.ofMinutes(3))
            itemEntity.setInstance(e.player.instance!!, e.player.position.add(0.0, 1.5, 0.0))
        }
        eventNode.addListener(PickupItemEvent::class.java) { e ->
            val player = e.entity as? Player ?: return@addListener

            if (player.gameMode != GameMode.ADVENTURE) {
                e.isCancelled = true
                return@addListener
            }

            val couldAdd = player.inventory.addItemStack(e.itemStack)
            e.isCancelled = !couldAdd
        }

        eventNode.addListener(EntityPreDeathEvent::class.java) { e ->
            val player = e.entity as? Player ?: return@addListener
            e.isCancelled = true

            if (e.damageType is CustomEntityDamage) kill(player, (e.damageType as CustomEntityDamage).entity)
            else kill(player)

        }

        eventNode.addListener(PlayerBlockInteractEvent::class.java) { e ->
            if (e.player.gameMode != GameMode.ADVENTURE) return@addListener
            if (e.block.compare(Block.CHEST)) {
                // Lazy loaded chests
                val handler = if (e.block.handler() is Dummy) {
                    val chest = SingleChestHandler.create()
                    val handler = chest.handler() as SingleChestHandler
                    e.instance.setBlock(e.blockPosition, chest)

                    val isRarerChest = e.instance.getBlock(e.blockPosition.add(0.0, 1.0, 0.0)).compare(Block.STRUCTURE_VOID)

                    repeat(7) {
                        handler.inventory.addRandomly(Items.randomItem(isRarerChest).itemStack)
                    }

                    chests.add(e.blockPosition)

                    handler
                } else {
                    e.block.handler() as SingleChestHandler
                }

                e.player.openInventory(handler.inventory)

                unopenedRefilledChests.remove(e.blockPosition)

                playerChestMap[e.player] = e.blockPosition

                val playersInside = handler.playersInside.incrementAndGet()
                val packet = BlockActionPacket(e.blockPosition, 1, playersInside.toByte(), Block.CHEST)
                PacketUtils.sendGroupedPacket(e.instance.players, packet)

                if (playersInside == 1) {
                    e.instance.playSound(
                        Sound.sound(SoundEvent.BLOCK_CHEST_OPEN, Sound.Source.BLOCK, 1f, 1f),
                        e.blockPosition.add(0.5, 0.5, 0.5)
                    )
                }

            }
        }

        eventNode.addListener(InventoryCloseEvent::class.java) { e ->
            val openChest = playerChestMap[e.player] ?: return@addListener
            val handler = e.instance.getBlock(openChest).handler() as? SingleChestHandler ?: return@addListener

            playerChestMap.remove(e.player)

            val playersInside = handler.playersInside.decrementAndGet()
            val packet = BlockActionPacket(openChest, 1, playersInside.toByte(), Block.CHEST)
            PacketUtils.sendGroupedPacket(e.instance.players, packet)

            if (playersInside == 0) e.instance.playSound(
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

        playSound(Sound.sound(Key.key("battle.death"), Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())


        if (killer != null && killer is Player) {
            killer.kills++

            killer.playSound(Sound.sound(SoundEvent.BLOCK_ANVIL_LAND, Sound.Source.MASTER, 0.35f, 2f), Sound.Emitter.self())

            player.scheduler().buildTask {
                player.showTitle(
                    Title.title(
                        Component.empty(),
                        Component.text()
                            .append(Component.text(killer.username, NamedTextColor.RED, TextDecoration.BOLD))
                            .append(Component.text(" was on ", TextColor.color(209, 50, 50)))
                            .append(Component.text("❤ ${killer.health.toInt()}/${killer.maxHealth.toInt()}", NamedTextColor.RED))
                            .build(),
                        Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1750), Duration.ofMillis(500))
                    )
                )
            }.delay(Duration.ofMillis(2500))

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
                    .append(Component.text(alivePlayers.size, NamedTextColor.RED))
                    .append(Component.text(" players left!", TextColor.color(209, 50, 50)))
            )

            scoreboard?.updateLineContent(
                "playersLeft",
                Component.text()
                    .append(Component.text("Players: ", NamedTextColor.GRAY))
                    .append(Component.text(alivePlayers.size, NamedTextColor.RED))
                    .build()
            )
        }

//        val rand = ThreadLocalRandom.current()
        player.inventory.itemStacks.filter { it.material() != blockedItemStack.material() }.forEach {
//            TODO: following code appears to cause issues
//            val angle = rand.nextDouble(PI * 2)
//            val strength = rand.nextDouble(3.0, 6.0)
//            val x = cos(angle) * strength
//            val z = sin(angle) * strength
//
//            val itemEntity = ItemEntity(it)
//            itemEntity.setPickupDelay(500, TimeUnit.MILLISECOND)
//            itemEntity.velocity = Vec(x, rand.nextDouble(3.0, 7.0), z)
//            itemEntity.setInstance(player.instance!!, player.position.add(0.0, 1.5, 0.0))
//
            player.dropItem(it)
        }

        player.inventory.clear()
    }

    private fun showdown() {
        players.forEachIndexed { i, player ->
            player.teleport(getCircleSpawnPosition(i))
        }

        borderActive = true

        instance!!.worldBorder.setCenter(centerPos.x.toFloat(), centerPos.z.toFloat())
        instance!!.worldBorder.diameter = (circleSize * 2) + 17.2
        instance!!.worldBorder.warningBlocks = 0

        playSound(Sound.sound(Key.key("battle.showdown"), Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())

        instance!!.scheduler().submitTask(object : Supplier<TaskSchedule> {
            var timeLeft = 60

            override fun get(): TaskSchedule {
                if (timeLeft == 0) {
                    victory(players.maxBy { it.kills })
                    return TaskSchedule.stop()
                }

                scoreboard?.updateLineContent("infoLine", Component.text("Game end in ${timeLeft.parsed()}", NamedTextColor.GREEN))

                playSound(Sound.sound(Key.key("battle.showdown.count${((60 - timeLeft) % 2) + 1}"), Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())

                timeLeft--
                return TaskSchedule.seconds(1)
            }
        })
    }

    private fun getCircleSpawnPosition(playerNum: Int): Pos {
        val angle = playerNum * ((2 * PI) / 8)
        val x = cos(angle) * circleSize
        val z = sin(angle) * circleSize

        var pos = Pos.fromPoint(centerPos.add(x, 0.0, z).roundToBlock().add(0.5, 0.0, 0.5))
        val angle1 = centerPos.sub(pos.x(), pos.y(), pos.z())

        pos = pos.withDirection(angle1).withPitch(0f)

        return pos
    }

    override fun gameEnded() {
        players.forEach {
            it.cleanup()
        }
    }

    override fun gameWon(winningPlayers: Collection<Player>) {
        playersInvulnerable = true

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

    override fun end() {
        if (gameState == GameState.DESTROYED) return
        gameState = GameState.DESTROYED

        LOGGER.info("Game ${gameTypeInfo.name}#$id is ending")

        gameEnded()



        startingTask?.cancel()
        startingTask = null

        // Both spectators and players

        val joinCountDown = CountDownLatch(players.size)

        players.shuffled().iterator().forEachRemaining {
            scoreboard?.removeViewer(it)

            val future = it.joinGameOrNew("battle", hasCooldown = false)

            if (future == null) {
                joinCountDown.countDown()
            } else {
                future.thenRun {
                    joinCountDown.countDown()
                }
            }
        }

        // Destroy game once all players have moved to a new one
        joinCountDown.toFuture().thenRun {
            // immortal destroy code - kinda scuffed but idc
            refreshPlayerCount()

            createFuture = null
            startingTask?.cancel()
            startingTask = null

            GameManager.removeGame(this)
            MinecraftServer.getInstanceManager().unregisterInstance(instance!!)
            instance = null
        }
    }

    override fun instanceCreate(): CompletableFuture<Instance> {
        val instanceFuture = CompletableFuture<Instance>()

        val mapPath = map ?: Files.list(Path.of("./battle-maps/"))
                .map { it.nameWithoutExtension }
                .collect(Collectors.toSet())
                .random()

        val newInstance = MinecraftServer.getInstanceManager().createInstanceContainer()
        newInstance.chunkLoader = AnvilLoader(Path.of("./battle-maps/${mapPath}"))
        newInstance.timeUpdate = null
        newInstance.timeRate = 0
        newInstance.setTag(GameManager.doNotAutoUnloadChunkTag, true)
        newInstance.explosionSupplier = PvpExplosionSupplier.INSTANCE
        newInstance.enableAutoChunkLoad(false)

        val config = ConsoleMinigamesMain.config.mapSpawnPositions[map]
        if (config == null) {
            LOGGER.warn("No config for map $map")
            throw NullPointerException("no config")
        }
        centerPos = config.circleCenterPos
        gameCenter = config.gameSpawnPos
        circleSize = config.circleSize

        val chunkXOff = centerPos.blockX() / 16
        val chunkZOff = centerPos.blockZ() / 16
        val radius = 8
        var i = 0
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                newInstance.loadChunk(chunkXOff + x, chunkZOff + z).thenAccept {
                    it.sendChunk()
                }
                i++
            }
        }

        newInstance.loadChunk(centerPos).thenRun {
            instanceFuture.complete(newInstance)
        }

        return instanceFuture
    }
}
