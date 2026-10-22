package com.doggame.platformer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private val VIRTUAL_W = 1280f
    private val VIRTUAL_H = 720f

    private enum class GameState { START, PLAYING, PAUSED, LEVEL_WIN, GAME_WIN, QUIZ }
    @Volatile private var gameState = GameState.START
    @Volatile private var pendingLevel = 0

    private enum class EnemyType { SPIKY, FLYER, HOPPER, CHASER }
    private enum class PickupType { BONE, GEM, HEART, STAR, WING }

    private val GRAVITY = 2200f
    private val MOVE_SPEED = 360f
    private val DOG_W = 70f
    private val DOG_H = 58f
    private val COYOTE = 0.1f
    private val JUMP_BUFFER = 0.12f

    private var power = 2
    private val JUMP_VELS = floatArrayOf(720f, 760f, 800f, 900f, 1020f)
    private val SIZE_SCALES = floatArrayOf(0.7f, 0.85f, 1.0f, 1.15f, 1.3f)
    private var invincible = 0f
    private var starTimer = 0f
    private var canDoubleJump = false
    private var airJumpsLeft = 0
    private var shield = 0
    private var coyoteTimer = 0f
    private var jumpBuffer = 0f
    private var stretch = 1f

    private var lives = 3
    private val quizLabels = arrayOf("北京", "上海", "重庆", "广州", "火星")
    private val quizOptions = arrayOf(
        RectF(125f, 430f, 315f, 495f),
        RectF(335f, 430f, 525f, 495f),
        RectF(545f, 430f, 735f, 495f),
        RectF(755f, 430f, 945f, 495f),
        RectF(965f, 430f, 1155f, 495f),
    )
    private var quizWrong = false
    private var quizCorrect = false
    private val btnQuizRestart = RectF(440f, 560f, 840f, 625f)

    @Volatile private var musicOn = true
    private val btnMusic = RectF(30f, 70f, 150f, 125f)
    private val btnPause = RectF(VIRTUAL_W - 90f, 70f, VIRTUAL_W - 30f, 130f)
    private val btnResume = RectF(440f, 320f, 840f, 385f)
    private val btnRestart = RectF(440f, 405f, 840f, 470f)

    private var dogX = 0f
    private var dogY = 0f
    private var vy = 0f
    private var onGround = false
    private var facing = 1f
    private var runPhase = 0f
    @Volatile private var jumpPressed = false
    private var prevJumpPressed = false

    private val sizeScale: Float get() = SIZE_SCALES[power]
    private val jumpVel: Float get() = JUMP_VELS[power]
    private val dogW: Float get() = DOG_W * sizeScale
    private val dogH: Float get() = DOG_H * sizeScale

    private var currentLevel = 1
    private val TOTAL_LEVELS = 3
    private val HEALTH_MAX = 3
    private val HEALTH_START = 3
    private var health = HEALTH_START
    private var levelBones = 0
    private var requiredBones = 5
    private var goalHintCooldown = 0f
    private var levelWidth = 3200f
    private var goalX = 3050f
    private val groundY = VIRTUAL_H - 40f
    private val deathLine = VIRTUAL_H + 20f
    private var checkpointX = 120f
    private var checkpointFlagX = 0f
    private var checkpointReached = false

    private val platforms = mutableListOf<Platform>()
    private val pickups = mutableListOf<Pickup>()
    private val enemies = mutableListOf<Enemy>()
    private val traps = mutableListOf<Trap>()
    private val floatingTexts = mutableListOf<FloatingText>()
    private val dusts = mutableListOf<Dust>()
    private val cannons = mutableListOf<Cannon>()
    private val bullets = mutableListOf<Bullet>()
    private val movingTraps = mutableListOf<MovingTrap>()
    private val bouncers = mutableListOf<Bouncer>()

    private var cameraX = 0f
    private var time = 0f
    private var shake = 0f
    private var qrBitmap: Bitmap? = null

    @Volatile private var leftPressed = false
    @Volatile private var rightPressed = false
    private val btnLeft = RectF(50f, 610f, 150f, 710f)
    private val btnRight = RectF(180f, 610f, 280f, 710f)
    private val btnJump = RectF(1120f, 600f, 1240f, 720f)

    private val sound = SoundEngine()

    private var thread: GameThread? = null
    @Volatile private var running = false
    @Volatile private var stopped = false
    private val paint = Paint()

    private data class Platform(val x: Float, val y: Float, val w: Float, val h: Float)
    private data class Trap(val x: Float, val y: Float, val w: Float, val h: Float)
    private class Pickup(val type: PickupType, val x: Float, val y: Float, var collected: Boolean = false)
    private class FloatingText(val x: Float, var y: Float, val text: String, var alpha: Float = 1f)
    private class Dust(var x: Float, var y: Float, val vx: Float, var life: Float, val r: Float, val vy: Float = 0f)
    private class Cannon(val x: Float, val y: Float, val interval: Float) {
        var timer = interval
    }
    private class Bullet(var x: Float, var y: Float, val vx: Float, val vy: Float)
    private class MovingTrap(val y: Float, val minX: Float, val maxX: Float, val speed: Float, val w: Float) {
        var x = minX
        var dir = 1f
    }
    private data class Bouncer(val x: Float, val y: Float, val w: Float)
    private class Enemy(
        val type: EnemyType, val x0: Float, val y0: Float,
        val minX: Float, val maxX: Float, val speed: Float, val r: Float,
        val amp: Float = 0f, val phase0: Float = 0f, val aggro: Float = 0f,
    ) {
        var x = x0
        var y = y0
        var dir = 1f
        var vy = 0f
        var hopTimer = 1.2f
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        qrBitmap = try { BitmapFactory.decodeResource(context.resources, R.drawable.qrcode) } catch (_: Exception) { null }
        buildLevel(1)
    }

    private fun addGround(x: Float, w: Float) = platforms.add(Platform(x, groundY, w, 40f))
    private fun addPlat(x: Float, y: Float, w: Float) = platforms.add(Platform(x, y, w, 24f))
    private fun addPickup(t: PickupType, x: Float, y: Float) = pickups.add(Pickup(t, x, y))

    private fun buildLevel(level: Int) {
        platforms.clear(); pickups.clear(); enemies.clear(); traps.clear(); floatingTexts.clear(); dusts.clear()
        cannons.clear(); bullets.clear(); movingTraps.clear(); bouncers.clear()
        checkpointX = 120f
        checkpointReached = false
        checkpointFlagX = if (level == 1) 1850f else 2200f
        levelBones = 0
        requiredBones = when (level) { 1 -> 5; 2 -> 6; else -> 8 }
        goalHintCooldown = 0f
        if (level == 1) {
            levelWidth = 3200f; goalX = 3050f
            addGround(0f, 600f); addGround(740f, 900f); addGround(1780f, 900f); addGround(2820f, 380f)
            listOf(
                Triple(620f, 570f, 180f), Triple(850f, 460f, 180f), Triple(1080f, 350f, 180f),
                Triple(1360f, 470f, 200f), Triple(1600f, 360f, 200f), Triple(1950f, 480f, 200f),
                Triple(2200f, 370f, 200f), Triple(2500f, 490f, 200f), Triple(2850f, 560f, 180f),
                Triple(1500f, 255f, 160f),
            ).forEach { addPlat(it.first, it.second, it.third) }
            enemies.add(Enemy(EnemyType.SPIKY, 800f, groundY - 22f, 800f, 1550f, 80f, 22f))
            enemies.add(Enemy(EnemyType.FLYER, 1400f, 420f, 1200f, 1700f, 85f, 18f, 45f, 0f))
            enemies.add(Enemy(EnemyType.HOPPER, 2100f, groundY - 22f, 2100f, 2100f, 0f, 22f))
            enemies.add(Enemy(EnemyType.FLYER, 1450f, 250f, 1280f, 1620f, 95f, 18f, 28f, 0f))
            traps.add(Trap(950f, groundY - 20f, 100f, 20f))
            traps.add(Trap(2900f, groundY - 20f, 100f, 20f))
            // 弹跳垫 + 高路线奖励捷径（跳过下方敌人）
            bouncers.add(Bouncer(1280f, groundY - 18f, 60f))
            addPlat(1260f, 300f, 160f)
            addPlat(1440f, 300f, 160f)
            addPlat(1620f, 300f, 160f)
            addPickup(PickupType.HEART, 740f, 520f)   // 护盾：第一波敌人(刺球+尖刺)之前的桥平台
            addPickup(PickupType.STAR, 2450f, 520f)   // 无敌星：冲沟(2680)+尖刺(2900)到终点
            addPickup(PickupType.GEM, 1300f, 260f)
            addPickup(PickupType.GEM, 1660f, 260f)
            addPickup(PickupType.BONE, 1500f, 210f)   // 高空捷径奖励
            // 地面主路线骨头：逼玩家下地，分散在敌人/尖刺附近
            addPickup(PickupType.BONE, 300f, 640f)
            addPickup(PickupType.BONE, 900f, 640f)
            addPickup(PickupType.BONE, 1300f, 640f)
            addPickup(PickupType.BONE, 1850f, 640f)
            addPickup(PickupType.BONE, 2400f, 640f)
            addPickup(PickupType.BONE, 2880f, 640f)
            addPickup(PickupType.GEM, 2100f, 600f)
        } else if (level == 2) {
            levelWidth = 4300f; goalX = 4150f
            addGround(0f, 550f); addGround(700f, 800f); addGround(1670f, 800f); addGround(2640f, 800f); addGround(3610f, 690f)
            listOf(
                Triple(560f, 570f, 180f), Triple(790f, 460f, 180f), Triple(1020f, 350f, 180f),
                Triple(1300f, 470f, 200f), Triple(1540f, 360f, 200f), Triple(1850f, 480f, 200f),
                Triple(2100f, 370f, 200f), Triple(2400f, 490f, 200f), Triple(2700f, 380f, 200f),
                Triple(3050f, 490f, 200f), Triple(3300f, 380f, 200f), Triple(3650f, 490f, 200f),
                Triple(3950f, 570f, 180f), Triple(2000f, 255f, 160f), Triple(3200f, 255f, 160f),
            ).forEach { addPlat(it.first, it.second, it.third) }
            enemies.add(Enemy(EnemyType.SPIKY, 850f, groundY - 22f, 800f, 1400f, 95f, 22f))
            enemies.add(Enemy(EnemyType.SPIKY, 1750f, groundY - 22f, 1700f, 2400f, 95f, 22f))
            enemies.add(Enemy(EnemyType.FLYER, 1100f, 430f, 950f, 1300f, 90f, 18f, 45f, 1.5f))
            enemies.add(Enemy(EnemyType.HOPPER, 2100f, groundY - 22f, 2100f, 2100f, 0f, 22f))
            enemies.add(Enemy(EnemyType.CHASER, 2850f, groundY - 22f, 2750f, 3350f, 110f, 20f, aggro = 280f))
            enemies.add(Enemy(EnemyType.FLYER, 3400f, 430f, 3250f, 3550f, 90f, 18f, 45f, 2.5f))
            enemies.add(Enemy(EnemyType.FLYER, 1570f, 245f, 1480f, 1680f, 95f, 18f, 26f, 1f))
            enemies.add(Enemy(EnemyType.FLYER, 3200f, 245f, 3180f, 3340f, 95f, 18f, 26f, 2f))
            traps.add(Trap(750f, groundY - 20f, 90f, 20f)); traps.add(Trap(1800f, groundY - 20f, 90f, 20f))
            traps.add(Trap(2900f, groundY - 20f, 90f, 20f)); traps.add(Trap(3700f, groundY - 20f, 90f, 20f))
            traps.add(Trap(1710f, 280f, 40f, 20f))   // 高空捷径平台上的尖刺
            movingTraps.add(MovingTrap(groundY - 20f, 2700f, 3400f, 150f, 90f))
            // 弹跳垫高路线 ×2
            bouncers.add(Bouncer(1470f, groundY - 18f, 60f))
            addPlat(1480f, 300f, 160f); addPlat(1660f, 300f, 160f)
            addPickup(PickupType.GEM, 1520f, 260f); addPickup(PickupType.GEM, 1700f, 260f)
            bouncers.add(Bouncer(3200f, groundY - 18f, 60f))
            addPlat(3180f, 300f, 160f)
            addPickup(PickupType.GEM, 3220f, 260f)   // 第二捷径奖励(原无敌星挪走)
            // 高空捷径额外奖励骨头
            addPickup(PickupType.BONE, 2000f, 210f); addPickup(PickupType.BONE, 3200f, 210f)
            // 护盾+无敌星前置，地面主路线骨头逼下地
            addPickup(PickupType.HEART, 700f, 520f)
            addPickup(PickupType.STAR, 2680f, 520f)
            addPickup(PickupType.BONE, 400f, 640f); addPickup(PickupType.BONE, 950f, 640f); addPickup(PickupType.BONE, 1900f, 640f)
            addPickup(PickupType.BONE, 2250f, 640f); addPickup(PickupType.BONE, 2650f, 640f); addPickup(PickupType.BONE, 3850f, 640f)
            addPickup(PickupType.BONE, 4050f, 640f)
            addPickup(PickupType.GEM, 2450f, 445f); addPickup(PickupType.GEM, 3100f, 445f)
            cannons.add(Cannon(3100f, groundY - 18f, 2.2f))
        } else {
            levelWidth = 5300f; goalX = 5150f
            addGround(0f, 500f); addGround(660f, 760f); addGround(1600f, 760f); addGround(2520f, 760f)
            addGround(3460f, 760f); addGround(4400f, 900f)
            listOf(
                Triple(520f, 570f, 180f), Triple(750f, 460f, 180f), Triple(980f, 350f, 180f),
                Triple(1260f, 470f, 200f), Triple(1500f, 360f, 200f), Triple(1800f, 480f, 200f),
                Triple(2050f, 370f, 200f), Triple(2350f, 490f, 200f), Triple(2600f, 380f, 200f),
                Triple(2900f, 490f, 200f), Triple(3150f, 380f, 200f), Triple(3500f, 490f, 200f),
                Triple(3750f, 380f, 200f), Triple(4100f, 480f, 200f), Triple(4400f, 570f, 180f),
                Triple(2000f, 250f, 160f), Triple(3050f, 250f, 160f), Triple(4200f, 250f, 160f),
            ).forEach { addPlat(it.first, it.second, it.third) }
            enemies.add(Enemy(EnemyType.SPIKY, 850f, groundY - 22f, 800f, 1500f, 115f, 22f))
            enemies.add(Enemy(EnemyType.SPIKY, 1800f, groundY - 22f, 1700f, 2450f, 115f, 22f))
            enemies.add(Enemy(EnemyType.SPIKY, 3600f, groundY - 22f, 3550f, 4300f, 115f, 22f))
            enemies.add(Enemy(EnemyType.FLYER, 1200f, 420f, 1050f, 1400f, 95f, 18f, 50f, 0.5f))
            enemies.add(Enemy(EnemyType.FLYER, 3300f, 410f, 3100f, 3500f, 100f, 18f, 50f, 2.2f))
            enemies.add(Enemy(EnemyType.HOPPER, 2600f, groundY - 22f, 2600f, 2600f, 0f, 22f))
            enemies.add(Enemy(EnemyType.CHASER, 3900f, groundY - 22f, 3700f, 4350f, 125f, 20f, aggro = 320f))
            enemies.add(Enemy(EnemyType.CHASER, 2900f, groundY - 22f, 2750f, 3200f, 120f, 20f, aggro = 300f))
            enemies.add(Enemy(EnemyType.FLYER, 2070f, 245f, 1980f, 2160f, 95f, 18f, 26f, 0f))
            enemies.add(Enemy(EnemyType.FLYER, 4400f, 245f, 4380f, 4540f, 95f, 18f, 26f, 1.5f))
            traps.add(Trap(720f, groundY - 20f, 90f, 20f)); traps.add(Trap(1750f, groundY - 20f, 90f, 20f))
            traps.add(Trap(2700f, groundY - 20f, 90f, 20f)); traps.add(Trap(3550f, groundY - 20f, 90f, 20f))
            traps.add(Trap(4500f, groundY - 20f, 90f, 20f))
            movingTraps.add(MovingTrap(groundY - 20f, 1700f, 2300f, 150f, 90f))
            movingTraps.add(MovingTrap(groundY - 20f, 3600f, 4150f, 150f, 90f))
            // 弹跳垫高路线
            bouncers.add(Bouncer(2000f, groundY - 18f, 60f))
            addPlat(1980f, 300f, 160f); addPlat(2160f, 300f, 160f)
            addPickup(PickupType.GEM, 2020f, 260f); addPickup(PickupType.WING, 2200f, 260f)
            bouncers.add(Bouncer(4480f, groundY - 18f, 60f))
            addPlat(4380f, 300f, 160f)
            addPickup(PickupType.GEM, 4420f, 260f)   // 最后捷径奖励(原护盾挪走)
            // 高空捷径额外奖励骨头
            addPickup(PickupType.BONE, 2000f, 205f); addPickup(PickupType.BONE, 3050f, 205f); addPickup(PickupType.BONE, 4200f, 205f)
            // 护盾+无敌星+二段跳前置，地面主路线骨头逼下地
            addPickup(PickupType.HEART, 660f, 520f)
            addPickup(PickupType.STAR, 3500f, 640f)
            addPickup(PickupType.WING, 3300f, 300f)
            addPickup(PickupType.BONE, 250f, 640f); addPickup(PickupType.BONE, 900f, 640f); addPickup(PickupType.BONE, 1200f, 640f)
            addPickup(PickupType.BONE, 1850f, 640f); addPickup(PickupType.BONE, 2100f, 640f); addPickup(PickupType.BONE, 2650f, 640f)
            addPickup(PickupType.BONE, 3050f, 640f); addPickup(PickupType.BONE, 3650f, 640f); addPickup(PickupType.BONE, 4100f, 640f)
            addPickup(PickupType.BONE, 4650f, 640f)
            addPickup(PickupType.GEM, 2400f, 445f); addPickup(PickupType.GEM, 3550f, 445f)
            cannons.add(Cannon(2900f, groundY - 18f, 1.8f))
            cannons.add(Cannon(4400f, groundY - 18f, 1.8f))
        }
        resetDog()
    }

    private fun resetDog() {
        dogX = checkpointX
        dogY = groundY - dogH
        vy = 0f
        onGround = false
        facing = 1f
        airJumpsLeft = if (canDoubleJump) 1 else 0
        stretch = 1f
    }

    // 变大/变小时保持脚底位置不变（向上长/向下缩），避免变大顶穿平台导致坠落判死
    private fun changePower(delta: Int) {
        val feetY = dogY + dogH
        power = (power + delta).coerceIn(0, 4)
        dogY = feetY - dogH
    }

    private fun spawnLandDust() {
        val cx = dogX + dogW / 2f
        val fy = dogY + dogH
        for (i in 0 until 5) {
            val sp = (i - 2f) * 55f
            dusts.add(Dust(cx, fy, sp, 0.4f, 5f + (i % 3) * 2f, vy = -40f))
        }
    }

    private fun spawnJumpDust() {
        val cx = dogX + dogW / 2f
        val fy = dogY + dogH
        for (i in 0 until 3) dusts.add(Dust(cx + (i - 1f) * 10f, fy, (i - 1f) * 25f, 0.3f, 4f, vy = -60f))
    }

    private fun spawnSparkle(x: Float, y: Float) {
        for (k in 0 until 6) {
            val ang = k * PI.toFloat() / 3f
            dusts.add(Dust(x, y, cos(ang) * 80f, 0.35f, 4f, sin(ang) * 80f))
        }
    }

    private fun die() {
        sound.death()
        lives--
        if (lives <= 0) {
            gameState = GameState.QUIZ
            quizWrong = false
            quizCorrect = false
            return
        }
        power = 2
        invincible = 0f
        starTimer = 0f
        canDoubleJump = false
        airJumpsLeft = 0
        shield = 0
        jumpBuffer = 0f
        coyoteTimer = 0f
        bullets.clear()
        health = HEALTH_START
        // 重生时恢复安全网道具(无敌星/羽毛/红心)，骨头保持已收集(防重复计数/长大)
        for (p in pickups) {
            if (p.type == PickupType.STAR || p.type == PickupType.WING || p.type == PickupType.HEART) {
                p.collected = false
            }
        }
        resetDog()
        for (e in enemies) { e.x = e.x0; e.dir = 1f }
    }

    // 统一受击：无敌/无敌星期间无伤；否则先耗护盾(挡一次)，再按伤害值扣血，血清零则死亡。
    // 敌人接触伤害=1，尖刺/移动尖刺/炮弹=2，掉坑直接死。返回 true 表示已死亡。
    private fun takeHit(damage: Int): Boolean {
        if (invincible > 0f || starTimer > 0f) return false
        when {
            shield > 0 -> {
                shield--
                invincible = 0.8f
                shake = 0.35f
                floatingTexts.add(FloatingText(dogX + dogW / 2f, dogY - 14f, "护盾挡下!"))
                sound.shrink()
            }
            health > 0 -> {
                health -= damage
                invincible = 0.8f
                shake = 0.35f
                floatingTexts.add(FloatingText(dogX + dogW / 2f, dogY - 14f, "-$damage 血"))
                sound.shrink()
                if (health <= 0) { die(); return true }
            }
            else -> { die(); return true }
        }
        return false
    }

    private fun startGame() {
        currentLevel = 1
        health = HEALTH_START
        power = 2
        lives = 3
        invincible = 0f
        starTimer = 0f
        canDoubleJump = false
        airJumpsLeft = 0
        shield = 0
        pendingLevel = 1
        gameState = GameState.PLAYING
    }

    private fun nextLevel() {
        currentLevel++
        health = HEALTH_START
        power = 2
        invincible = 0f
        starTimer = 0f
        canDoubleJump = false
        airJumpsLeft = 0
        shield = 0
        pendingLevel = currentLevel
        gameState = GameState.PLAYING
    }

    private fun toggleMusic() {
        musicOn = !musicOn
        if (musicOn) sound.startMusic() else sound.stopMusic()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { running = true; ensureThread() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        sound.stopMusic()
    }

    fun resume() { running = true; ensureThread(); if (musicOn) sound.startMusic() }
    fun pause() { running = false; sound.stopMusic() }

    @Synchronized private fun ensureThread() {
        if (stopped) return
        if (thread == null || !thread!!.isAlive) { thread = GameThread(); thread!!.start() }
    }

    fun stop() {
        stopped = true
        running = false
        try { thread?.interrupt() } catch (_: Exception) {}
    }

    private inner class GameThread : Thread() {
        override fun run() {
            var last = System.nanoTime()
            while (!stopped) {
                if (!running) {
                    try { sleep(30) } catch (_: InterruptedException) { break }
                    last = System.nanoTime()
                    continue
                }
                val now = System.nanoTime()
                val dt = ((now - last) / 1e9).toFloat().coerceIn(0f, 0.05f)
                last = now
                time += dt
                if (pendingLevel != 0) {
                    buildLevel(pendingLevel)
                    pendingLevel = 0
                }
                if (gameState == GameState.PLAYING) {
                    if (invincible > 0f) invincible -= dt
                    if (starTimer > 0f) starTimer -= dt
                }
                if (shake > 0f) shake -= dt
                stretch += (1f - stretch) * 10f * dt
                if (gameState == GameState.PLAYING) update(dt)
                for (ft in floatingTexts.toList()) { ft.y -= 40f * dt; ft.alpha -= 1.5f * dt }
                floatingTexts.removeAll { it.alpha <= 0f }
                for (d in dusts.toList()) { d.x += d.vx * dt; d.y += d.vy * dt; d.life -= dt }
                dusts.removeAll { it.life <= 0f }
                draw()
                try { sleep(10) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun update(dt: Float) {
        var dir = 0f
        if (leftPressed) dir -= 1f
        if (rightPressed) dir += 1f
        if (dir != 0f) facing = dir

        if (jumpPressed && !prevJumpPressed) jumpBuffer = JUMP_BUFFER
        prevJumpPressed = jumpPressed
        if (jumpBuffer > 0f) jumpBuffer -= dt

        dogX += dir * MOVE_SPEED * dt
        if (dogX < 0f) dogX = 0f
        if (dogX > levelWidth - dogW) dogX = levelWidth - dogW

        vy += GRAVITY * dt
        val prevDogY = dogY
        dogY += vy * dt

        val wasOnGround = onGround
        onGround = false
        if (vy >= 0f) {
            for (p in platforms) {
                if (dogX + dogW > p.x && dogX < p.x + p.w &&
                    prevDogY + dogH <= p.y + 0.5f && dogY + dogH >= p.y
                ) {
                    dogY = p.y - dogH
                    vy = 0f
                    onGround = true
                    airJumpsLeft = if (canDoubleJump) 1 else 0
                    break
                }
            }
            // 弹跳垫：落地时高弹跳
            if (!onGround) {
                for (bp in bouncers) {
                    if (dogX + dogW > bp.x && dogX < bp.x + bp.w &&
                        prevDogY + dogH <= bp.y + 0.5f && dogY + dogH >= bp.y
                    ) {
                        dogY = bp.y - dogH
                        vy = -1300f
                        stretch = 1.3f
                        airJumpsLeft = if (canDoubleJump) 1 else 0
                        spawnJumpDust()
                        sound.jump()
                        break
                    }
                }
            }
        }
        if (onGround) coyoteTimer = COYOTE else coyoteTimer -= dt
        if (onGround && !wasOnGround) { stretch = 0.8f; spawnLandDust() }

        // 跳跃（土狼时间 + 缓冲 + 二段跳）
        if (jumpBuffer > 0f) {
            if (onGround || coyoteTimer > 0f) {
                vy = -jumpVel
                onGround = false
                coyoteTimer = 0f
                jumpBuffer = 0f
                airJumpsLeft = if (canDoubleJump) 1 else 0
                stretch = 1.25f
                spawnJumpDust()
                sound.jump()
            } else if (airJumpsLeft > 0) {
                airJumpsLeft--
                vy = -jumpVel
                jumpBuffer = 0f
                stretch = 1.25f
                sound.jump()
            }
        }

        if (onGround && dir != 0f) runPhase += dt * 12f else runPhase = 0f

        for (e in enemies) {
            when (e.type) {
                EnemyType.SPIKY -> {
                    e.x += e.speed * e.dir * dt
                    if (e.x < e.minX) { e.x = e.minX; e.dir = 1f }
                    if (e.x > e.maxX) { e.x = e.maxX; e.dir = -1f }
                }
                EnemyType.FLYER -> {
                    e.x += e.speed * e.dir * dt
                    if (e.x < e.minX) { e.x = e.minX; e.dir = 1f }
                    if (e.x > e.maxX) { e.x = e.maxX; e.dir = -1f }
                    e.y = e.y0 + sin(time * 2f + e.phase0) * e.amp
                }
                EnemyType.HOPPER -> {
                    e.hopTimer -= dt
                    if (e.hopTimer <= 0f && e.y >= e.y0) { e.vy = -700f; e.hopTimer = 1.2f }
                    if (e.vy != 0f || e.y < e.y0) {
                        e.vy += GRAVITY * dt
                        e.y += e.vy * dt
                        if (e.y >= e.y0) { e.y = e.y0; e.vy = 0f }
                    }
                }
                EnemyType.CHASER -> {
                    val dx = dogX + dogW / 2f - e.x
                    if (abs(dx) < e.aggro) {
                        val dirSign = if (dx > 0f) 1f else -1f
                        e.x += e.speed * dirSign * dt
                    } else {
                        e.x += e.speed * e.dir * dt
                        if (e.x < e.minX) { e.x = e.minX; e.dir = 1f }
                        if (e.x > e.maxX) { e.x = e.maxX; e.dir = -1f }
                    }
                    e.x = e.x.coerceIn(e.minX, e.maxX)
                }
            }
        }

        // 炮台发射 + 子弹飞行
        for (cn in cannons) {
            cn.timer -= dt
            if (cn.timer <= 0f) {
                cn.timer = cn.interval
                val tx = dogX + dogW / 2f - cn.x
                val ty = dogY + dogH / 2f - cn.y
                val len = Math.sqrt((tx * tx + ty * ty).toDouble()).toFloat().coerceAtLeast(1f)
                val sp = 420f
                bullets.add(Bullet(cn.x, cn.y, tx / len * sp, ty / len * sp))
                sound.collect()
            }
        }
        for (b in bullets) { b.x += b.vx * dt; b.y += b.vy * dt }
        bullets.removeAll { it.x < -60f || it.x > levelWidth + 60f || it.y < -60f || it.y > VIRTUAL_H + 60f }
        for (mt in movingTraps) {
            mt.x += mt.speed * mt.dir * dt
            if (mt.x < mt.minX) { mt.x = mt.minX; mt.dir = 1f }
            if (mt.x > mt.maxX) { mt.x = mt.maxX; mt.dir = -1f }
        }

        val dogRect = RectF(dogX, dogY, dogX + dogW, dogY + dogH)

        for (e in enemies) {
            if (dogRect.intersect(e.x - e.r, e.y - e.r, e.x + e.r, e.y + e.r)) { if (takeHit(1)) return }
        }
        for (t in traps) {
            if (dogRect.intersect(t.x, t.y, t.x + t.w, t.y + t.h)) { if (takeHit(2)) return }
        }
        for (b in bullets) {
            if (dogRect.intersect(b.x - 8f, b.y - 8f, b.x + 8f, b.y + 8f)) { if (takeHit(2)) return }
        }
        for (mt in movingTraps) {
            if (dogRect.intersect(mt.x, mt.y, mt.x + mt.w, mt.y + 20f)) { if (takeHit(2)) return }
        }
        if (dogY > deathLine) { die(); return }

        for (p in pickups) {
            if (!p.collected && dogRect.intersect(p.x - 20f, p.y - 20f, p.x + 20f, p.y + 20f)) {
                p.collected = true
                spawnSparkle(p.x, p.y)
                when (p.type) {
                    PickupType.BONE -> {
                        levelBones++
                        if (power < 4) {
                            changePower(1)
                            sound.grow()
                            floatingTexts.add(FloatingText(p.x, p.y, "变大!"))
                        } else {
                            sound.collect()
                            floatingTexts.add(FloatingText(p.x, p.y, "+1"))
                        }
                    }
                    PickupType.GEM -> {
                        if (health < HEALTH_MAX) {
                            health++
                            floatingTexts.add(FloatingText(p.x, p.y, "+1 血"))
                        } else if (shield < 1) {
                            shield++
                            floatingTexts.add(FloatingText(p.x, p.y, "+护盾"))
                        }
                        sound.sparkle()
                    }
                    PickupType.HEART -> {
                        if (health < HEALTH_MAX) {
                            health = HEALTH_MAX
                            floatingTexts.add(FloatingText(p.x, p.y, "回满血!"))
                        } else if (lives < 5) {
                            lives++
                            floatingTexts.add(FloatingText(p.x, p.y, "+生命"))
                        }
                        sound.grow()
                    }
                    PickupType.STAR -> {
                        starTimer = 4f
                        sound.sparkle()
                        floatingTexts.add(FloatingText(p.x, p.y, "无敌!"))
                    }
                    PickupType.WING -> {
                        canDoubleJump = true
                        airJumpsLeft = if (onGround) 1 else airJumpsLeft + 1
                        sound.collect()
                        floatingTexts.add(FloatingText(p.x, p.y, "二段跳!"))
                    }
                }
            }
        }

        if (!checkpointReached && dogX + dogW >= checkpointFlagX) {
            checkpointReached = true
            checkpointX = checkpointFlagX
            floatingTexts.add(FloatingText(checkpointFlagX, groundY - 70f, "检查点!"))
            sound.grow()
        }

        if (dogX + dogW >= goalX) {
            if (levelBones >= requiredBones) {
                sound.levelWin()
                if (currentLevel >= TOTAL_LEVELS) gameState = GameState.GAME_WIN
                else gameState = GameState.LEVEL_WIN
            } else {
                goalHintCooldown -= dt
                if (goalHintCooldown <= 0f) {
                    goalHintCooldown = 1.4f
                    floatingTexts.add(FloatingText(goalX - 60f, groundY - 100f, "还需 ${requiredBones - levelBones} 根骨头!"))
                }
            }
        } else {
            goalHintCooldown = 0f
        }

        val targetCam = (dogX - VIRTUAL_W * 0.4f).coerceIn(0f, levelWidth - VIRTUAL_W)
        cameraX += (targetCam - cameraX) * min(1f, 8f * dt)
    }

    private fun draw() {
        val h = holder
        if (h == null || !h.surface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas = h.lockCanvas() ?: return
            val scale = min(canvas.width / VIRTUAL_W, canvas.height / VIRTUAL_H)
            val ox = (canvas.width - VIRTUAL_W * scale) / 2f
            val oy = (canvas.height - VIRTUAL_H * scale) / 2f
            drawFullScreenBackground(canvas, scale, oy)
            canvas.save()
            canvas.translate(ox, oy)
            canvas.scale(scale, scale)
            if (shake > 0f) {
                val s = shake * 12f
                canvas.translate((Math.random() * 2 - 1).toFloat() * s, (Math.random() * 2 - 1).toFloat() * s)
            }
            drawWorld(canvas)
            canvas.restore()
        } finally {
            if (canvas != null) h.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawFullScreenBackground(c: Canvas, scale: Float, oy: Float) {
        val w = c.width.toFloat()
        val h = c.height.toFloat()
        // 天空渐变（铺满全屏，消除黑边）
        val grad = android.graphics.LinearGradient(
            0f, 0f, 0f, h,
            Color.rgb(120, 195, 245), Color.rgb(190, 228, 250),
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = grad
        paint.color = Color.WHITE
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        // 太阳
        paint.color = Color.rgb(255, 220, 100)
        c.drawCircle(w * 0.9f, h * 0.1f, h * 0.09f, paint)
        // 视差山丘（全屏，底部对齐游戏地面）
        val groundScreenY = oy + (VIRTUAL_H + 20f) * scale
        drawHillsScreen(c, 0.25f, Color.rgb(160, 210, 150), groundScreenY + 40f * scale, 130f * scale, scale)
        drawHillsScreen(c, 0.5f, Color.rgb(120, 180, 120), groundScreenY + 20f * scale, 100f * scale, scale)
        // 云
        for (i in 0..4) {
            val cx = ((i * w / 4f - (cameraX * 0.2f * scale) % (w / 2f)) % w + w) % w
            drawCloud(c, cx, h * 0.1f + (i % 2) * h * 0.09f)
        }
    }

    private fun drawHillsScreen(c: Canvas, parallax: Float, color: Int, baseY: Float, radius: Float, scale: Float) {
        paint.color = color
        paint.style = Paint.Style.FILL
        val spacing = radius * 1.5f
        val off = (cameraX * parallax * scale) % spacing
        var x = -radius - off
        while (x < c.width + radius) {
            c.drawCircle(x, baseY, radius, paint)
            x += spacing
        }
    }

    private fun drawWorld(c: Canvas) {
        c.save()
        c.translate(-cameraX, 0f)

        for (p in platforms.toList()) {
            paint.color = Color.rgb(101, 67, 33)
            c.drawRect(p.x, p.y, p.x + p.w, p.y + p.h, paint)
            paint.color = Color.rgb(120, 190, 90)
            c.drawRect(p.x, p.y, p.x + p.w, p.y + 8f, paint)
            drawGrass(c, p)
        }
        for (t in traps.toList()) drawTrap(c, t)
        for (mt in movingTraps.toList()) drawMovingTrap(c, mt)
        for (bp in bouncers.toList()) drawBouncer(c, bp)
        for (p in pickups.toList()) if (!p.collected) drawPickup(c, p)
        for (e in enemies.toList()) drawEnemy(c, e)
        for (cn in cannons.toList()) drawCannon(c, cn)
        for (b in bullets.toList()) drawBullet(c, b)
        drawGoal(c)
        drawCheckpoint(c)
        for (d in dusts.toList()) {
            paint.color = Color.argb((d.life * 3f * 255).toInt().coerceIn(0, 255), 240, 240, 230)
            paint.style = Paint.Style.FILL
            c.drawCircle(d.x, d.y, d.r + (0.4f - d.life) * 8f, paint)
        }
        drawDog(c, dogX, dogY)
        for (ft in floatingTexts.toList()) {
            paint.color = Color.argb((ft.alpha * 255).toInt(), 255, 220, 60)
            paint.textSize = 34f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            c.drawText(ft.text, ft.x, ft.y, paint)
            paint.textAlign = Paint.Align.LEFT
        }

        c.restore()

        drawHud(c)
        drawButtons(c)
        when (gameState) {
            GameState.START -> drawStart(c)
            GameState.PAUSED -> drawPause(c)
            GameState.LEVEL_WIN -> drawLevelWin(c)
            GameState.GAME_WIN -> drawGameWin(c)
            GameState.QUIZ -> drawQuiz(c)
            else -> {}
        }
    }

    private fun drawHills(c: Canvas, parallax: Float, color: Int, baseY: Float, radius: Float) {
        paint.color = color
        paint.style = Paint.Style.FILL
        val spacing = radius * 1.5f
        val offset = -(cameraX * parallax % spacing)
        var x = offset - radius
        while (x < VIRTUAL_W + radius) {
            c.drawCircle(x, baseY, radius, paint)
            x += spacing
        }
    }

    private fun drawGrass(c: Canvas, p: Platform) {
        paint.color = Color.rgb(90, 160, 70)
        val n = (p.w / 26f).toInt().coerceAtMost(30)
        for (i in 0 until n) {
            val gx = p.x + 6f + i * 26f
            val path = Path()
            path.moveTo(gx, p.y)
            path.lineTo(gx + 5f, p.y - 7f)
            path.lineTo(gx + 10f, p.y)
            path.close()
            c.drawPath(path, paint)
        }
    }

    private fun drawHud(c: Canvas) {
        drawMusicButton(c)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 255, 255, 255)
        c.drawRoundRect(btnPause, 12f, 12f, paint)
        paint.color = Color.argb(220, 60, 60, 60)
        c.drawRect(btnPause.left + 22f, btnPause.top + 14f, btnPause.left + 30f, btnPause.bottom - 14f, paint)
        c.drawRect(btnPause.right - 30f, btnPause.top + 14f, btnPause.right - 22f, btnPause.bottom - 14f, paint)

        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT_BOLD

        // 生命血条（长度随血量增减）
        drawHealthBar(c)

        // 命数 + 骨头
        paint.color = Color.rgb(255, 90, 90)
        paint.textSize = 30f
        c.drawText("♥".repeat(lives.coerceIn(0, 5)), 180f, 122f, paint)
        paint.color = Color.rgb(255, 236, 205)
        paint.textSize = 28f
        c.drawText("骨头 $levelBones/$requiredBones", 250f, 122f, paint)

        // 状态徽章（右侧）
        var sy = 74f
        if (shield > 0) {
            paint.color = Color.rgb(120, 200, 255)
            paint.textSize = 28f
            c.drawText("护盾", 440f, sy, paint)
            sy += 30f
        }
        if (starTimer > 0f) {
            paint.color = Color.rgb(255, 210, 60)
            paint.textSize = 28f
            c.drawText("无敌 ${(starTimer + 0.99f).toInt()}s", 440f, sy, paint)
            sy += 30f
        } else if (canDoubleJump) {
            paint.color = Color.rgb(140, 220, 240)
            paint.textSize = 28f
            c.drawText("二段跳", 440f, sy, paint)
        }

        // 第 X/Y 关
        paint.color = Color.WHITE
        paint.textSize = 40f
        paint.textAlign = Paint.Align.RIGHT
        c.drawText("第 $currentLevel / $TOTAL_LEVELS 关", VIRTUAL_W - 30f, 60f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawHealthBar(c: Canvas) {
        val bx = 180f
        val by = 66f
        val bw = 240f
        val bh = 20f
        // 背景
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(190, 40, 40, 45)
        c.drawRoundRect(RectF(bx, by, bx + bw, by + bh), 10f, 10f, paint)
        // 左侧心形图标
        drawHeart(c, bx - 18f, by + bh / 2f, 11f)
        // 红色填充（长度 = 血量占比）
        val frac = (health.toFloat() / HEALTH_MAX.toFloat()).coerceIn(0f, 1f)
        if (frac > 0f) {
            paint.color = Color.rgb(229, 57, 53)
            val pad = 3f
            c.drawRoundRect(RectF(bx + pad, by + pad, bx + pad + (bw - pad * 2f) * frac, by + bh - pad), 7f, 7f, paint)
        }
        // 数值
        paint.color = Color.WHITE
        paint.textSize = 20f
        paint.textAlign = Paint.Align.CENTER
        c.drawText("$health/$HEALTH_MAX", bx + bw / 2f, by + bh / 2f + 7f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawMusicButton(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = if (musicOn) Color.argb(210, 80, 165, 90) else Color.argb(210, 120, 120, 120)
        c.drawRoundRect(btnMusic, 14f, 14f, paint)
        paint.color = Color.WHITE
        paint.textSize = 30f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        c.drawText(if (musicOn) "音乐 开" else "音乐 关", btnMusic.centerX(), btnMusic.centerY() + 11f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawCloud(c: Canvas, x: Float, y: Float) {
        paint.color = Color.WHITE
        c.drawCircle(x, y, 30f, paint)
        c.drawCircle(x + 28f, y + 8f, 24f, paint)
        c.drawCircle(x - 28f, y + 8f, 22f, paint)
    }

    private fun drawPickup(c: Canvas, p: Pickup) {
        val bob = sin(time * 3f + p.x) * 4f
        val cy = p.y + bob
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(45, 255, 255, 255)
        c.drawCircle(p.x, cy, 26f, paint)
        when (p.type) {
            PickupType.BONE -> {
                paint.color = Color.rgb(255, 236, 205)
                c.drawRoundRect(RectF(p.x - 20f, cy - 5f, p.x + 20f, cy + 5f), 4f, 4f, paint)
                c.drawCircle(p.x - 18f, cy - 7f, 7f, paint)
                c.drawCircle(p.x - 18f, cy + 7f, 7f, paint)
                c.drawCircle(p.x + 18f, cy - 7f, 7f, paint)
                c.drawCircle(p.x + 18f, cy + 7f, 7f, paint)
            }
            PickupType.GEM -> drawStar(c, p.x, cy, 16f, Color.rgb(255, 200, 40))
            PickupType.HEART -> drawHeart(c, p.x, cy, 22f)
            PickupType.STAR -> drawStar(c, p.x, cy, 18f, if ((time * 4).toInt() % 2 == 0) Color.rgb(255, 220, 60) else Color.WHITE)
            PickupType.WING -> {
                paint.color = Color.rgb(128, 222, 234)
                c.drawOval(RectF(p.x - 15f, cy - 6f, p.x + 15f, cy + 6f), paint)
                paint.color = Color.WHITE
                paint.strokeWidth = 3f
                c.drawLine(p.x - 11f, cy, p.x + 11f, cy, paint)
                paint.strokeWidth = 0f
            }
        }
    }

    private fun drawStar(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val path = Path()
        for (i in 0 until 10) {
            val ang = -PI.toFloat() / 2f + i * PI.toFloat() / 5f
            val rr = if (i % 2 == 0) r else r * 0.45f
            val x = cx + cos(ang) * rr
            val y = cy + sin(ang) * rr
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawPath(path, paint)
    }

    private fun drawHeart(c: Canvas, cx: Float, cy: Float, s: Float) {
        paint.color = Color.rgb(229, 57, 53)
        c.drawCircle(cx - s / 2f, cy - s / 4f, s / 2f, paint)
        c.drawCircle(cx + s / 2f, cy - s / 4f, s / 2f, paint)
        val path = Path()
        path.moveTo(cx - s, cy - s / 4f)
        path.lineTo(cx + s, cy - s / 4f)
        path.lineTo(cx, cy + s)
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawEnemy(c: Canvas, e: Enemy) {
        val r = e.r
        paint.style = Paint.Style.FILL
        when (e.type) {
            EnemyType.SPIKY, EnemyType.HOPPER, EnemyType.CHASER -> {
                val bodyColor = when (e.type) {
                    EnemyType.SPIKY -> Color.rgb(210, 60, 60)
                    EnemyType.HOPPER -> Color.rgb(90, 180, 90)
                    else -> Color.rgb(240, 200, 40)
                }
                paint.color = bodyColor
                c.drawCircle(e.x, e.y, r, paint)
                paint.color = Color.rgb(Color.red(bodyColor) / 2, Color.green(bodyColor) / 2, Color.blue(bodyColor) / 2)
                paint.strokeWidth = 5f
                val step = 2f * PI.toFloat() / 8f
                for (k in 0 until 8) {
                    val ang = k * step + time * 1.5f
                    val sx = e.x + cos(ang) * (r + 5f)
                    val sy = e.y + sin(ang) * (r + 5f)
                    val ex = e.x + cos(ang) * (r + 13f)
                    val ey = e.y + sin(ang) * (r + 13f)
                    c.drawLine(sx, sy, ex, ey, paint)
                }
                paint.strokeWidth = 0f
                paint.color = Color.WHITE
                c.drawCircle(e.x - 5f, e.y - 3f, 4f, paint)
                c.drawCircle(e.x + 5f, e.y - 3f, 4f, paint)
                paint.color = Color.BLACK
                c.drawCircle(e.x - 5f, e.y - 3f, 2f, paint)
                c.drawCircle(e.x + 5f, e.y - 3f, 2f, paint)
            }
            EnemyType.FLYER -> {
                paint.color = Color.rgb(70, 90, 200)
                c.drawCircle(e.x, e.y, r, paint)
                paint.color = Color.rgb(50, 65, 160)
                val wing = sin(time * 10f + e.phase0) * 6f
                val lp = Path()
                lp.moveTo(e.x - r, e.y); lp.lineTo(e.x - r - 14f, e.y - 12f + wing); lp.lineTo(e.x - r - 14f, e.y + 12f + wing); lp.close()
                c.drawPath(lp, paint)
                val rp = Path()
                rp.moveTo(e.x + r, e.y); rp.lineTo(e.x + r + 14f, e.y - 12f - wing); rp.lineTo(e.x + r + 14f, e.y + 12f - wing); rp.close()
                c.drawPath(rp, paint)
                paint.color = Color.WHITE
                c.drawCircle(e.x - 5f, e.y - 3f, 4f, paint)
                c.drawCircle(e.x + 5f, e.y - 3f, 4f, paint)
                paint.color = Color.BLACK
                c.drawCircle(e.x - 5f, e.y - 3f, 2f, paint)
                c.drawCircle(e.x + 5f, e.y - 3f, 2f, paint)
            }
        }
    }

    private fun drawCannon(c: Canvas, cn: Cannon) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(70, 70, 82)
        c.drawRoundRect(RectF(cn.x - 22f, cn.y - 15f, cn.x + 22f, cn.y + 15f), 6f, 6f, paint)
        paint.color = Color.rgb(200, 60, 60)
        c.drawCircle(cn.x + 18f, cn.y, 10f, paint)
        // 前摇闪烁：即将发射时炮口变亮
        val flash = cn.timer < 0.4f
        if (flash) {
            paint.color = Color.argb(180, 255, 220, 120)
            c.drawCircle(cn.x + 18f, cn.y, 12f + (0.4f - cn.timer) * 20f, paint)
        }
    }

    private fun drawBullet(c: Canvas, b: Bullet) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(255, 90, 80)
        c.drawCircle(b.x, b.y, 8f, paint)
        paint.color = Color.argb(110, 255, 170, 150)
        c.drawCircle(b.x - b.vx * 0.02f, b.y - b.vy * 0.02f, 5f, paint)
    }

    private fun drawBouncer(c: Canvas, bp: Bouncer) {
        paint.style = Paint.Style.FILL
        // 底座
        paint.color = Color.rgb(150, 120, 90)
        c.drawRect(bp.x, bp.y + 10f, bp.x + bp.w, bp.y + 20f, paint)
        // 弹跳面
        paint.color = Color.rgb(255, 150, 40)
        c.drawRoundRect(RectF(bp.x, bp.y, bp.x + bp.w, bp.y + 12f), 5f, 5f, paint)
        // 上箭头
        paint.color = Color.WHITE
        val cx = bp.x + bp.w / 2f
        val path = Path()
        path.moveTo(cx - 10f, bp.y + 10f)
        path.lineTo(cx, bp.y + 2f)
        path.lineTo(cx + 10f, bp.y + 10f)
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawMovingTrap(c: Canvas, mt: MovingTrap) {
        // 轨道线 + 端点
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(90, 255, 255, 255)
        c.drawRect(mt.minX, groundY - 2f, mt.maxX, groundY + 2f, paint)
        paint.color = Color.argb(160, 255, 200, 100)
        c.drawRect(mt.minX - 4f, groundY - 7f, mt.minX + 4f, groundY + 7f, paint)
        c.drawRect(mt.maxX - 4f, groundY - 7f, mt.maxX + 4f, groundY + 7f, paint)
        // 尖刺块
        val spikeW = 16f
        val n = (mt.w / spikeW).toInt()
        paint.color = Color.rgb(160, 60, 60)
        for (i in 0 until n) {
            val sx = mt.x + i * spikeW
            val path = Path()
            path.moveTo(sx, mt.y + 20f)
            path.lineTo(sx + spikeW / 2f, mt.y)
            path.lineTo(sx + spikeW, mt.y + 20f)
            path.close()
            c.drawPath(path, paint)
        }
    }

    private fun drawTrap(c: Canvas, t: Trap) {
        val spikeW = 16f
        val n = (t.w / spikeW).toInt()
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(120, 120, 132)
        for (i in 0 until n) {
            val sx = t.x + i * spikeW
            val path = Path()
            path.moveTo(sx, t.y + t.h)
            path.lineTo(sx + spikeW / 2f, t.y)
            path.lineTo(sx + spikeW, t.y + t.h)
            path.close()
            c.drawPath(path, paint)
        }
    }

    private fun drawCheckpoint(c: Canvas) {
        if (checkpointReached) return
        val cx = checkpointFlagX
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(120, 140, 160)
        c.drawRect(cx - 3f, groundY - 90f, cx + 3f, groundY, paint)
        paint.color = Color.rgb(90, 140, 220)
        val flag = Path()
        flag.moveTo(cx + 3f, groundY - 90f)
        flag.lineTo(cx + 45f, groundY - 78f)
        flag.lineTo(cx + 3f, groundY - 66f)
        flag.close()
        c.drawPath(flag, paint)
        paint.color = Color.WHITE
        paint.textSize = 26f
        c.drawText("检查点", cx - 8f, groundY - 98f, paint)
    }

    private fun drawGoal(c: Canvas) {
        val gx = goalX
        val locked = levelBones < requiredBones
        paint.color = Color.rgb(90, 90, 90)
        c.drawRect(gx - 4f, 400f, gx + 4f, groundY, paint)
        paint.color = if (locked) Color.rgb(150, 150, 150) else Color.rgb(230, 60, 60)
        val wave = sin(time * 3f) * 8f
        val flag = Path()
        flag.moveTo(gx + 4f, 400f)
        flag.lineTo(gx + 90f + wave, 425f)
        flag.lineTo(gx + 4f, 450f)
        flag.close()
        c.drawPath(flag, paint)
        paint.color = Color.WHITE
        paint.textSize = 30f
        c.drawText(if (locked) "需 ${requiredBones - levelBones} 骨头" else "终点", gx - 10f, 385f, paint)
    }

    private fun drawDog(c: Canvas, x: Float, y: Float) {
        val cx = x + dogW / 2f
        val cy = y + dogH / 2f
        c.save()
        if ((starTimer > 0f || invincible > 0f) && (time * 8).toInt() % 2 == 0) paint.alpha = 140
        c.scale(facing * sizeScale * (2f - stretch), sizeScale * stretch, cx, cy)

        val body = Color.rgb(214, 152, 78)
        val dark = Color.rgb(150, 96, 40)
        paint.style = Paint.Style.FILL

        // 摇动的尾巴
        val wag = sin(time * 6f)
        paint.color = body
        paint.strokeWidth = 8f
        c.drawLine(cx - 34f, cy - 6f, cx - 52f, cy - 18f + wag * 8f, paint)

        paint.color = body
        c.drawOval(RectF(cx - 26f, cy - 14f, cx + 30f, cy + 14f), paint)

        paint.color = dark
        val swing = if (onGround) sin(runPhase) * 8f else 2f
        c.drawRect(cx - 18f, cy + 10f, cx - 8f, cy + 26f + swing, paint)
        c.drawRect(cx + 2f, cy + 10f, cx + 12f, cy + 26f - swing, paint)
        c.drawRect(cx + 16f, cy + 10f, cx + 26f, cy + 26f + swing, paint)

        paint.color = body
        c.drawCircle(cx + 38f, cy - 6f, 20f, paint)

        paint.color = dark
        c.drawCircle(cx + 28f, cy - 24f, 9f, paint)
        c.drawCircle(cx + 48f, cy - 24f, 9f, paint)

        paint.color = Color.rgb(40, 30, 20)
        val blink = time % 3.5f < 0.12f
        if (blink) {
            c.drawRect(cx + 42f, cy - 9f, cx + 52f, cy - 6f, paint)
        } else {
            c.drawCircle(cx + 46f, cy - 8f, 3f, paint)
        }
        c.drawCircle(cx + 57f, cy - 4f, 4f, paint)

        c.restore()
        paint.alpha = 255
        paint.strokeWidth = 0f
        if (shield > 0) {
            paint.style = Paint.Style.STROKE
            paint.color = Color.argb(190, 120, 200, 255)
            paint.strokeWidth = 5f
            c.drawCircle(cx, cy, dogW * 0.75f, paint)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
        }
    }

    private fun drawButtons(c: Canvas) {
        drawBtnCircle(c, btnLeft, 50f, "←", leftPressed)
        drawBtnCircle(c, btnRight, 50f, "→", rightPressed)
        drawBtnCircle(c, btnJump, 60f, "跳", jumpPressed)
    }

    private fun drawBtnCircle(c: Canvas, r: RectF, radius: Float, label: String, pressed: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) Color.argb(170, 255, 255, 255) else Color.argb(110, 255, 255, 255)
        c.drawCircle(r.centerX(), r.centerY(), radius + if (pressed) 3f else 0f, paint)
        paint.color = Color.argb(200, 40, 40, 40)
        paint.textSize = 36f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.LEFT
        c.drawText(label, r.centerX() - 14f, r.centerY() + 13f, paint)
    }

    private fun overlay(c: Canvas) = c.drawColor(Color.argb(170, 0, 0, 0))

    private fun drawRoundedBtn(c: Canvas, r: RectF, label: String, bg: Int) {
        paint.style = Paint.Style.FILL
        paint.color = bg
        c.drawRoundRect(r, 14f, 14f, paint)
        paint.color = Color.WHITE
        paint.textSize = 36f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        c.drawText(label, r.centerX(), r.centerY() + 13f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawStart(c: Canvas) {
        overlay(c)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val bounce = sin(time * 2f) * 8f
        paint.color = Color.rgb(255, 220, 100)
        paint.textSize = 100f
        c.drawText("红球狗狗", VIRTUAL_W / 2f, 230f + bounce, paint)
        paint.color = Color.WHITE
        paint.textSize = 38f
        c.drawText("吃骨头变大跳更高，攒够骨头才能过终点", VIRTUAL_W / 2f, 320f, paint)
        c.drawText("被刺球撞会变小，躲开尖刺和坑", VIRTUAL_W / 2f, 365f, paint)
        paint.textSize = 40f
        c.drawText("点击屏幕开始", VIRTUAL_W / 2f, 440f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawPause(c: Canvas) {
        overlay(c)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = 80f
        c.drawText("已暂停", VIRTUAL_W / 2f, 250f, paint)
        drawRoundedBtn(c, btnResume, "继续", Color.argb(230, 80, 165, 90))
        drawRoundedBtn(c, btnRestart, "重新开始", Color.argb(230, 200, 90, 60))
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawLevelWin(c: Canvas) {
        overlay(c)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(255, 220, 100)
        paint.textSize = 80f
        c.drawText("第 $currentLevel 关完成！", VIRTUAL_W / 2f, 220f, paint)
        val total = pickups.size
        val collected = pickups.count { it.collected }
        val stars = if (total == 0) 3 else when {
            collected >= total -> 3
            collected >= total * 0.6 -> 2
            else -> 1
        }
        paint.textSize = 60f
        paint.color = Color.rgb(255, 210, 60)
        c.drawText("★".repeat(stars) + "☆".repeat(3 - stars), VIRTUAL_W / 2f, 310f, paint)
        paint.color = Color.WHITE
        paint.textSize = 36f
        c.drawText("收集 $collected / $total", VIRTUAL_W / 2f, 360f, paint)
        paint.textSize = 40f
        c.drawText("点击进入下一关", VIRTUAL_W / 2f, 430f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawGameWin(c: Canvas) {
        overlay(c)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(255, 220, 100)
        paint.textSize = 72f
        c.drawText("您真是聪明的狗狗！", VIRTUAL_W / 2f, 180f, paint)
        paint.color = Color.WHITE
        paint.textSize = 40f
        c.drawText("更多好玩关卡，期待红球工作室新版发布！", VIRTUAL_W / 2f, 240f, paint)
        // 二维码
        val qr = qrBitmap
        if (qr != null) {
            val size = 200f
            val qx = VIRTUAL_W / 2f - size / 2f
            val qy = 280f
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            c.drawRoundRect(RectF(qx - 12f, qy - 12f, qx + size + 12f, qy + size + 12f), 18f, 18f, paint)
            val src = Rect(0, 0, qr.width, qr.height)
            val dst = RectF(qx, qy, qx + size, qy + size)
            c.drawBitmap(qr, src, dst, paint)
        }
        paint.color = Color.rgb(255, 220, 100)
        paint.textSize = 38f
        c.drawText("更多精彩，扫扫码下载红球助手！", VIRTUAL_W / 2f, 520f, paint)
        paint.color = Color.WHITE
        paint.textSize = 32f
        c.drawText("恭喜通关！点击重新开始", VIRTUAL_W / 2f, 590f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawQuiz(c: Canvas) {
        overlay(c)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        if (quizCorrect) {
            paint.color = Color.rgb(120, 255, 150)
            paint.textSize = 58f
            c.drawText("答对了，你真是聪明的狗狗！", VIRTUAL_W / 2f, 300f, paint)
            drawRoundedBtn(c, btnQuizRestart, "重新开始", Color.argb(230, 80, 165, 90))
        } else {
            paint.color = Color.rgb(255, 220, 100)
            paint.textSize = 80f
            c.drawText("游戏结束", VIRTUAL_W / 2f, 160f, paint)
            paint.color = Color.WHITE
            paint.textSize = 48f
            c.drawText("红球工作室是哪里的？", VIRTUAL_W / 2f, 280f, paint)
            if (quizWrong) {
                paint.color = Color.rgb(255, 120, 120)
                paint.textSize = 42f
                c.drawText("答错了是小狗，哈哈哈！请重答", VIRTUAL_W / 2f, 355f, paint)
            }
            for (i in quizOptions.indices) {
                val o = quizOptions[i]
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(230, 60, 60, 60)
                c.drawRoundRect(o, 14f, 14f, paint)
                paint.color = Color.WHITE
                paint.textSize = 38f
                c.drawText(quizLabels[i], o.centerX(), o.centerY() + 13f, paint)
            }
        }
        paint.textAlign = Paint.Align.LEFT
    }

    private fun virtualCoords(event: MotionEvent, index: Int): Pair<Float, Float> {
        val scale = min(width / VIRTUAL_W, height / VIRTUAL_H)
        val ox = (width - VIRTUAL_W * scale) / 2f
        val oy = (height - VIRTUAL_H * scale) / 2f
        return Pair((event.getX(index) - ox) / scale, (event.getY(index) - oy) / scale)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (tx, ty) = virtualCoords(event, 0)
                if (btnMusic.contains(tx, ty)) { toggleMusic(); return true }
                if (btnPause.contains(tx, ty)) {
                    if (gameState == GameState.PLAYING) { gameState = GameState.PAUSED; return true }
                }
                when (gameState) {
                    GameState.START -> startGame()
                    GameState.PAUSED -> {
                        if (btnResume.contains(tx, ty)) gameState = GameState.PLAYING
                        else if (btnRestart.contains(tx, ty)) startGame()
                    }
                    GameState.LEVEL_WIN -> nextLevel()
                    GameState.GAME_WIN -> startGame()
                    GameState.QUIZ -> {
                        if (quizCorrect) {
                            if (btnQuizRestart.contains(tx, ty)) startGame()
                        } else handleQuizTap(tx, ty)
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                leftPressed = false; rightPressed = false; jumpPressed = false
                return true
            }
        }
        updateButtons(event)
        return true
    }

    private fun handleQuizTap(tx: Float, ty: Float) {
        for (i in quizOptions.indices) {
            if (quizOptions[i].contains(tx, ty)) {
                if (i == 2) { quizCorrect = true; sound.levelWin() }
                else { quizWrong = true; sound.shrink() }
                return
            }
        }
    }

    private fun updateButtons(event: MotionEvent) {
        if (gameState != GameState.PLAYING) {
            leftPressed = false; rightPressed = false; jumpPressed = false
            return
        }
        val scale = min(width / VIRTUAL_W, height / VIRTUAL_H)
        val ox = (width - VIRTUAL_W * scale) / 2f
        val oy = (height - VIRTUAL_H * scale) / 2f
        val lifted = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        var l = false; var r = false; var j = false
        for (i in 0 until event.pointerCount) {
            if (i == lifted) continue
            val tx = (event.getX(i) - ox) / scale
            val ty = (event.getY(i) - oy) / scale
            if (btnLeft.contains(tx, ty)) l = true
            if (btnRight.contains(tx, ty)) r = true
            if (btnJump.contains(tx, ty)) j = true
        }
        leftPressed = l; rightPressed = r; jumpPressed = j
    }
}
