package dev.emortal.consoleminigames.game

import dev.emortal.consoleminigames.ConsoleMinigamesExtension
import dev.emortal.consoleminigames.game.BattlePlayerHelper.cleanup
import dev.emortal.consoleminigames.game.BattlePlayerHelper.kills
import dev.emortal.consoleminigames.item.Items
import dev.emortal.consoleminigames.item.addRandomly
import dev.emortal.immortal.game.GameManager
import dev.emortal.immortal.game.GameManager.joinGameOrNew
import dev.emortal.immortal.game.GameState
import dev.emortal.immortal.game.PvpGame
import dev.emortal.immortal.util.MinestomRunnable
import dev.emortal.immortal.util.armify
import dev.emortal.immortal.util.parsed
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
import net.minestom.server.utils.PacketUtils
import net.minestom.server.utils.time.TimeUnit
import org.tinylog.kotlin.Logger
import world.cepi.kstom.Manager
import world.cepi.kstom.event.listenOnly
import world.cepi.kstom.util.asVec
import world.cepi.kstom.util.roundToBlock
import world.cepi.particle.Particle
import world.cepi.particle.ParticleType
import world.cepi.particle.data.OffsetAndSpeed
import world.cepi.particle.showParticle
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors
import kotlin.io.path.nameWithoutExtension
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin


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

        eventNode.listenOnly<InventoryPreClickEvent> {
            if (clickedItem.material() == blockedItemStack.material()) {
                isCancelled = true
                this.cursorItem = ItemStack.AIR
            }
        }

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

        Logger.info("Registering legacyEvents")
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
        instance!!.eventNode().listenOnly<PlayerMoveEvent> {
            if (!gameStarted)
                if (!this.player.position.samePoint(newPosition))
                    isCancelled = true
        }

        var secondsToStart = 10
        var invulnerabilitySeconds = 15

        val secsUntilShowdown = players.size * 1.5 * 60L

        bossBar = BossBar.bossBar(Component.text("Time to start: $secondsToStart seconds"), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS)
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
        object : MinestomRunnable(repeat = Duration.ofSeconds(1), iterations = secondsToStart, group = runnableGroup) {
            var lastNum = secondsToStart

            override fun run() {
                bossBar!!.progress(1f - (currentIteration.toFloat() / iterations.toFloat()))

                bossBar!!.name(Component.text("Time to start: $lastNum seconds"))
                if (lastNum <= 5) {
                    playSound(
                        Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_BASS, Sound.Source.MASTER, 2f, 1f),
                        Sound.Emitter.self()
                    )
                }
                lastNum--
            }

            override fun cancelled() {
                playSound(Sound.sound(SoundEvent.ENTITY_ENDER_DRAGON_GROWL, Sound.Source.MASTER, 1f, 1.3f))

                bossBar!!.name(Component.text("Round start!"))
                bossBar!!.progress(1f)

                // Showdown timer
                object : MinestomRunnable(repeat = Duration.ofSeconds(1), iterations = secsUntilShowdown.toInt(), group = runnableGroup) {

                    override fun run() {
                        scoreboard?.updateLineContent(
                            "infoLine",
                            Component.text()
                                .append(Component.text("Showdown in ", TextColor.color(59, 128, 59)))
                                .append(Component.text((iterations - currentIteration.get()).parsed(), NamedTextColor.GREEN))
                                .build()
                        )
                    }

                    override fun cancelled() {
                        showdown()
                    }

                }

                gameStarted = true

                // Invincibility timer
                object : MinestomRunnable(delay = Duration.ofSeconds(1), repeat = Duration.ofSeconds(1), iterations = invulnerabilitySeconds, group = runnableGroup) {
                    var lastNum = invulnerabilitySeconds
                    var progress = 1f

                    override fun run() {
                        bossBar!!.progress(1f - (currentIteration.toFloat() / iterations.toFloat()))

                        bossBar!!.name(Component.text("Invulnerability wears off in $lastNum seconds!"))
                        if (lastNum <= 3) {
                            playSound(Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_BASS, Sound.Source.MASTER, 2f, 1f))
                        }
                        lastNum--
                    }

                    override fun cancelled() {
                        playSound(Sound.sound(SoundEvent.ENTITY_ELDER_GUARDIAN_CURSE, Sound.Source.MASTER, 1f, 0.7f), Sound.Emitter.self())

                        bossBar!!.progress(0f)
                        bossBar!!.name(Component.text("Invulnerability has worn off! Fight!"))
                        sendMessage(
                            Component.text()
                                .append(Component.text("☠", NamedTextColor.RED))
                                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                                .append(Component.text("Invulnerability", NamedTextColor.WHITE))
                                .append(Component.text(" has worn off!", NamedTextColor.GRAY))
                                .append(Component.text(" Fight!", NamedTextColor.WHITE))
                        )

                        playersInvulnerable = false

                        instance!!.scheduler().buildTask {
                            hideBossBar(bossBar!!)
                        }.delay(Duration.ofSeconds(2)).schedule()
                    }
                }
            }
        }

        // Chest refill task
        val refillInterval = Duration.ofSeconds(70)
        object : MinestomRunnable(delay = refillInterval, repeat = refillInterval, group = runnableGroup) {
            override fun run() {
                if (gameState == GameState.ENDING) return

                val chestsRefilled = refillChests()
                if (chestsRefilled == 0) return

                sendMessage(
                    Component.text()
                        .append(Component.text("★", NamedTextColor.YELLOW))
                        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("$chestsRefilled chests", NamedTextColor.WHITE))
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
                instance!!.scheduler().buildTask {
                    playSound(Sound.sound(SoundEvent.BLOCK_ENDER_CHEST_CLOSE, Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())
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
            }
        }

        // Refill chest particle task
        object : MinestomRunnable(repeat = Duration.ofMillis(50), delay = refillInterval, group = runnableGroup) {
            override fun run() {
//                val rand = ThreadLocalRandom.current()

                unopenedRefilledChests.forEach {
                    showParticle(
                        Particle.particle(
                            type = ParticleType.CRIT,
                            count = 0,
                            data = OffsetAndSpeed(0f, 0.2f, 0f, 0.05f)
                        ), it.asVec().add(0.5, 1.0, 0.5)
                    )
                }
            }
        }
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
        val alivePlayers = players.filter { !it.hasTag(GameManager.spectatingTag) }
        if (alivePlayers.size == 1) {
            if (gameState != GameState.PLAYING) return

            victory(alivePlayers.first())
        }
    }

    @Suppress("UnstableApiUsage")
    override fun registerEvents(eventNode: EventNode<InstanceEvent>) {

        eventNode.listenOnly<EntityTickEvent> {
            if (entity.entityType == EntityType.ITEM && !entity.isOnFire) {
                val block = instance.getBlock(entity.position)
                if (block.compare(Block.LAVA) || block.compare(Block.FIRE)) {
                    entity.isOnFire = true
                    entity.scheduler().buildTask {
                        instance.playSound(Sound.sound(SoundEvent.ENTITY_GENERIC_BURN, Sound.Source.MASTER, 0.7f, 1f), entity.position)
                        entity.remove()
                    }.delay(Duration.ofMillis(500)).schedule()
                }
            }
        }

        eventNode.listenOnly<PlayerTickEvent> {
//            val insideBlock = instance.getBlock(player.position)

            if (borderActive && player.gameMode == GameMode.ADVENTURE) {
                val point = player.position
                val radius: Double = (instance.worldBorder.diameter / 2.0) + 1.5
                val checkX = point.x() <= instance.worldBorder.centerX + radius && point.x() >= instance.worldBorder.centerX - radius
                val checkZ = point.z() <= instance.worldBorder.centerZ + radius && point.z() >= instance.worldBorder.centerZ - radius

                if (!checkX || !checkZ) {
                    kill(player)
                }
            }

            val blocksInHitbox = pointsBetween(player.boundingBox.relativeStart().add(player.position), player.boundingBox.relativeEnd().add(player.position))

            if (blocksInHitbox.any { instance.getBlock(it).compare(Block.WATER) }) {
                player.isOnFire = false
            }
            if (blocksInHitbox.any { instance.getBlock(it).compare(Block.FIRE) }) {
                EntityUtils.setFireForDuration(player, Duration.ofSeconds(6))

                if (player.aliveTicks % 10L == 0L) {
                    if (player.activeEffects.any { it.potion.effect == PotionEffect.FIRE_RESISTANCE }) return@listenOnly
                    player.damage(CustomDamageType.IN_FIRE, 1.0f)
                }
            }
            if (blocksInHitbox.any { instance.getBlock(it).compare(Block.LAVA) }) {
                EntityUtils.setFireForDuration(player, Duration.ofSeconds(12))

                if (player.aliveTicks % 10L == 0L) {
                    if (player.activeEffects.any { it.potion.effect == PotionEffect.FIRE_RESISTANCE }) return@listenOnly
                    player.damage(CustomDamageType.LAVA, 4.0f)
                }
            }
        }

        eventNode.listenOnly<ExplosionEvent> {
            this.affectedBlocks.clear()
        }
        eventNode.listenOnly<PlayerBlockPlaceEvent> {
            if (block.compare(Block.TNT)) {
                isCancelled = true
                ExplosionListener.primeTnt(instance, blockPosition, player, 40)
                player.setItemInHand(hand, if (player.itemInMainHand.amount() == 1) ItemStack.AIR else player.itemInMainHand.withAmount(player.itemInMainHand.amount() - 1))

                return@listenOnly
            }
        }

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
                // Lazy loaded chests
                val handler = if (block.handler() is Dummy) {
                    val chest = SingleChestHandler.create()
                    val handler = chest.handler() as SingleChestHandler
                    instance.setBlock(blockPosition, chest)

                    val isRarerChest = instance.getBlock(blockPosition.add(0.0, 1.0, 0.0)).compare(Block.STRUCTURE_VOID)

                    repeat(7) {
                        handler.inventory.addRandomly(Items.randomItem(isRarerChest).itemStack)
                    }

                    chests.add(blockPosition)

                    handler
                } else {
                    block.handler() as SingleChestHandler
                }

                player.openInventory(handler.inventory)

                unopenedRefilledChests.remove(blockPosition)

                playerChestMap[player] = blockPosition

                val playersInside = handler.playersInside.incrementAndGet()
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

            player.sendMessage(
                Component.text()
                    .append(Component.text(killer.username, NamedTextColor.RED, TextDecoration.BOLD))
                    .append(Component.text(" was on ", TextColor.color(209, 50, 50)))
                    .append(Component.text("❤ ${killer.health.toInt()}/${killer.maxHealth.toInt()}", NamedTextColor.RED))
                    .also {
                        if (killer.health < 1.5) {
                            it.append(Component.text(" (so close!)", TextColor.color(82, 11, 11)))
                        }
                    }
            )

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

    private fun showdown() {
        players.forEachIndexed { i, player ->
            player.teleport(getCircleSpawnPosition(i))
        }

        borderActive = true

        instance!!.worldBorder.setCenter(centerPos.x.toFloat(), centerPos.z.toFloat())
        instance!!.worldBorder.setDiameter((circleSize * 2) + 1.5)
        instance!!.worldBorder.warningBlocks = 0

        playSound(Sound.sound(SoundEvent.ENTITY_WITHER_SPAWN, Sound.Source.MASTER, 0.25f, 2f), Sound.Emitter.self())

        showTitle(
            Title.title(
                Component.text("SHOWDOWN", NamedTextColor.RED, TextDecoration.BOLD),
                Component.empty(),
                Title.Times.times(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1))
            )
        )

        sendMessage(
            Component.text()
                .append(Component.text("☠", NamedTextColor.GOLD))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Showdown has activated!", NamedTextColor.GOLD))
                .build()
        )
        sendMessage(
            Component.text()
                .append(Component.text("☠", NamedTextColor.GOLD))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Game ends in 2 minutes!", NamedTextColor.GOLD))
                .build()
        )

        object : MinestomRunnable(repeat = Duration.ofSeconds(1), iterations = 2 * 60, group = runnableGroup) {

            override fun run() {
                scoreboard?.updateLineContent("infoLine", Component.text("Game end in ${(iterations - currentIteration.get()).parsed()}", NamedTextColor.GREEN))
            }

            override fun cancelled() {
                victory(players.maxBy { it.kills })
            }

        }

        players.forEach {
            it.isGlowing = true
        }
    }

    private fun getCircleSpawnPosition(playerNum: Int): Pos {
        val angle = playerNum * ((2 * PI) / 8)
        val x = cos(angle) * circleSize
        val z = sin(angle) * circleSize

        var pos = centerPos.add(x, 0.0, z).roundToBlock().add(0.5, 0.0, 0.5)
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

        Logger.info("Game ${gameTypeInfo.name}#$id is ending")

//        Manager.globalEvent.removeChild(eventNode)
//        eventNode = null

        gameEnded()



        startingTask?.cancel()
        startingTask = null
        runnableGroup.cancelAll()

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

            MinecraftServer.getInstanceManager().unregisterInstance(instance!!)

            createFuture = null
            startingTask?.cancel()
            startingTask = null
            instance = null

            GameManager.removeGame(this)
        }
    }

    override fun instanceCreate(): CompletableFuture<Instance> {
        val instanceFuture = CompletableFuture<Instance>()

        val mapPath = map
            ?: Files.list(Path.of("./battle-maps/"))
                .map { it.nameWithoutExtension }
                .collect(Collectors.toSet())
                .random()

        val newInstance = Manager.instance.createInstanceContainer()
        newInstance.chunkLoader = AnvilLoader(Path.of("./battle-maps/${mapPath}"))
        newInstance.timeUpdate = null
        newInstance.timeRate = 0
        newInstance.setTag(GameManager.doNotAutoUnloadChunkTag, true)
        newInstance.explosionSupplier = PvpExplosionSupplier.INSTANCE
        newInstance.enableAutoChunkLoad(false)

        val config = ConsoleMinigamesExtension.config.mapSpawnPositions[map]
        if (config == null) {
            Logger.warn("No config for map $map")
            throw NullPointerException("no config")
        }
        centerPos = config.circleCenterPos
        gameCenter = config.gameSpawnPos
        circleSize = config.circleSize

        val chunkXOff = centerPos.blockX() / 16
        val chunkZOff = centerPos.blockZ() / 16
        val radius = 8
        val chunkFutures = mutableListOf<CompletableFuture<Chunk>>()
        var i = 0
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                newInstance.loadChunk(chunkXOff + x, chunkZOff + z).let { chunkFutures.add(it) }
                i++
            }
        }

        CompletableFuture.allOf(*chunkFutures.toTypedArray()).thenRunAsync {
            instanceFuture.complete(newInstance)
        }

        Logger.info("Spawns: $config")
        Logger.info(centerPos.toString())

        return instanceFuture
    }
}
