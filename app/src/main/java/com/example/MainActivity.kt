package com.example

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.isActive
import java.util.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ==========================================
// LANE RUNNER ENUMS AND DATA STRUCTURES
// ==========================================

enum class Lane(val index: Int) {
    LEFT(0),
    CENTER(1),
    RIGHT(2)
}

enum class GameState {
    START_SCREEN,
    PLAYING,
    GAME_OVER
}

enum class ObstacleType {
    LOW_HURDLE,      // Can jump over or swipe to dodge
    TALL_WALL        // MUST swipe to dodge (cannot jump over)
}

enum class CoinType {
    GROUND,
    AIR             // MUST jump to collect
}

enum class PowerUpType {
    SHIELD,         // Absorbs 1 crash
    MAGNET          // Pulls coins from adjacent lanes
}

data class Obstacle(
    val id: Long = UUID.randomUUID().mostSignificantBits,
    val lane: Lane,
    val type: ObstacleType,
    var progress: Float = 0f, // 0.0f (horizon) to 1.1f (bottom, off-screen)
    var isNetted: Boolean = false // Cleared or crashed
)

data class Coin(
    val id: Long = UUID.randomUUID().mostSignificantBits,
    val lane: Lane,
    val type: CoinType,
    var progress: Float = 0f,
    var isCollected: Boolean = false
)

data class PowerUpItem(
    val id: Long = UUID.randomUUID().mostSignificantBits,
    val lane: Lane,
    val type: PowerUpType,
    var progress: Float = 0f,
    var isCollected: Boolean = false
)

data class GameParticle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val color: Color,
    val size: Float,
    var alpha: Float = 1f,
    val lifeDecay: Float = 0.04f
)

data class ScorePopup(
    val text: String,
    val lane: Lane,
    var progress: Float, // Syncs roughly around athlete position or stays floating
    var alpha: Float = 1f,
    var yOffset: Float = 0f,
    val color: Color = Color(0xFFFFD700)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_scaffold")
                ) { innerPadding ->
                    GameContainer(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun GameContainer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("LaneRunnerPrefs", Context.MODE_PRIVATE) }
    
    // Game state states
    var gameState by remember { mutableStateOf(GameState.START_SCREEN) }
    var score by remember { mutableFloatStateOf(0f) }
    var coinsCollected by remember { mutableIntStateOf(0) }
    var highScore by remember { mutableFloatStateOf(sharedPrefs.getFloat("high_score", 0f)) }
    
    // Player controls & positioning
    var currentLane by remember { mutableStateOf(Lane.CENTER) }
    
    // Lane interpolation (Slide Animation)
    val laneTargetX = when (currentLane) {
        Lane.LEFT -> -1.0f
        Lane.CENTER -> 0f
        Lane.RIGHT -> 1.0f
    }
    val playerHorizontalOffset by animateFloatAsState(
        targetValue = laneTargetX,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "player_lane_transition"
    )

    // Jump Physics (Parabolic Motion)
    var isJumping by remember { mutableStateOf(false) }
    var jumpYOffset by remember { mutableFloatStateOf(0f) } // 0 is ground, negative is air
    var jumpVelocity by remember { mutableFloatStateOf(0f) }
    val gravity = 0.7f
    val jumpPower = -14.5f

    // Running animation time ticker
    var runAnimationCycle by remember { mutableFloatStateOf(0f) }
    
    // Entities
    val obstacles = remember { mutableStateListOf<Obstacle>() }
    val coins = remember { mutableStateListOf<Coin>() }
    val powerUpsItems = remember { mutableStateListOf<PowerUpItem>() }
    val particles = remember { mutableStateListOf<GameParticle>() }
    val popups = remember { mutableStateListOf<ScorePopup>() }

    // Active power-up durations (in seconds/frames)
    var shieldDurationLeft by remember { mutableFloatStateOf(0f) } // in ticks (approx 60 ticks per sec)
    var magnetDurationLeft by remember { mutableFloatStateOf(0f) }

    // Environment and speed progression
    var gameSpeed by remember { mutableFloatStateOf(0.008f) } // rate of progress per tick
    var roadDecorationScroll by remember { mutableFloatStateOf(0f) }
    var lastSpawnTime by remember { mutableLongStateOf(0L) }
    
    // Swipe gestures state tracking
    var accumulatedDragX by remember { mutableFloatStateOf(0f) }
    var accumulatedDragY by remember { mutableFloatStateOf(0f) }
    var gestureRegistered by remember { mutableStateOf(false) }

    // Reset game handler
    fun resetGame() {
        obstacles.clear()
        coins.clear()
        powerUpsItems.clear()
        particles.clear()
        popups.clear()
        score = 0f
        coinsCollected = 0
        currentLane = Lane.CENTER
        isJumping = false
        jumpYOffset = 0f
        jumpVelocity = 0f
        shieldDurationLeft = 0f
        magnetDurationLeft = 0f
        gameSpeed = 0.008f
        roadDecorationScroll = 0f
        lastSpawnTime = 0L
    }

    // Save score helper
    fun handleGameOver() {
        gameState = GameState.GAME_OVER
        if (score > highScore) {
            highScore = score
            sharedPrefs.edit().putFloat("high_score", score).apply()
        }
        
        // Spawn crash particle burst
        val crashLaneX = when (currentLane) {
            Lane.LEFT -> -0.4f
            Lane.CENTER -> 0f
            Lane.RIGHT -> 0.4f
        }
        repeat(30) {
            particles.add(
                GameParticle(
                    x = crashLaneX,
                    y = 0.85f,
                    vx = (Math.random() * 0.04 - 0.02).toFloat(),
                    vy = (Math.random() * 0.04 - 0.03).toFloat(),
                    color = if (shieldDurationLeft > 0) Color(0xFF00BFFF) else Color(0xFFFF4500),
                    size = (8 + Math.random() * 12).toFloat(),
                    lifeDecay = (0.015 + Math.random() * 0.02).toFloat()
                )
            )
        }
    }

    // 60FPS Game loop inside LaunchedEffect
    LaunchedEffect(gameState) {
        if (gameState == GameState.PLAYING) {
            while (isActive) {
                playFrameTick(
                    obstacles = obstacles,
                    coins = coins,
                    powerUpsItems = powerUpsItems,
                    particles = particles,
                    popups = popups,
                    currentLane = currentLane,
                    playerHorizontalOffset = playerHorizontalOffset,
                    isJumping = isJumping,
                    jumpYOffset = jumpYOffset,
                    shieldDuration = shieldDurationLeft,
                    magnetDuration = magnetDurationLeft,
                    gameSpeed = gameSpeed,
                    onScoreTick = { score += 0.15f * (1f + (score / 1000f).toInt()) },
                    onCoinsTick = { coinsCollected++ },
                    onShieldBreak = {
                        shieldDurationLeft = 0f
                        // Trigger shield blast visual
                        repeat(20) {
                            particles.add(
                                GameParticle(
                                    x = playerHorizontalOffset * 0.4f,
                                    y = 0.82f + jumpYOffset / 1000f,
                                    vx = (Math.random() * 0.03 - 0.015).toFloat(),
                                    vy = (Math.random() * 0.03 - 0.015).toFloat(),
                                    color = Color(0xFF00F5FF),
                                    size = (6 + Math.random() * 10).toFloat(),
                                    lifeDecay = 0.03f
                                )
                            )
                        }
                        popups.add(ScorePopup("SHIELD SHATTERED!", currentLane, 0.85f, color = Color(0xFF00FFFF)))
                    },
                    onCrash = { handleGameOver() },
                    onReward = { bonusReward -> score += bonusReward },
                    onShieldCollect = { shieldDurationLeft = 400f }, // ~6.5 seconds
                    onMagnetCollect = { magnetDurationLeft = 500f }, // ~8.3 seconds
                    onSpawnEntities = { currentTime ->
                        // Wave spawning mechanic based on current speed
                        if (currentTime - lastSpawnTime > maxOf(900, 1600 - (gameSpeed * 20000).toLong())) {
                            lastSpawnTime = currentTime
                            spawnRandomObstaclesAndCoins(obstacles, coins, powerUpsItems)
                        }
                    },
                    updatePlayerAnimation = {
                        runAnimationCycle += 0.22f + gameSpeed * 1.5f
                        if (runAnimationCycle > Math.PI.toFloat() * 2) {
                            runAnimationCycle -= Math.PI.toFloat() * 2
                        }
                    },
                    updateShieldDuration = { val next = shieldDurationLeft - 1f; shieldDurationLeft = maxOf(0f, next) },
                    updateMagnetDuration = { val next = magnetDurationLeft - 1f; magnetDurationLeft = maxOf(0f, next) },
                    updateGameSpeed = {
                        // Slowly speed up as score increases
                        gameSpeed = 0.008f + (score / 6000f) * 0.003f
                        if (gameSpeed > 0.016f) gameSpeed = 0.016f
                        roadDecorationScroll = (roadDecorationScroll + (gameSpeed * 80f)) % 100f
                    }
                )
                
                // Track standard jumping trajectory physics
                if (isJumping) {
                    jumpYOffset += jumpVelocity
                    jumpVelocity += gravity
                    if (jumpYOffset >= 0f) {
                        jumpYOffset = 0f
                        isJumping = false
                        jumpVelocity = 0f
                        
                        // Spawn dust puff on landing
                        val landingX = playerHorizontalOffset * 0.4f
                        repeat(10) {
                            particles.add(
                                GameParticle(
                                    x = landingX,
                                    y = 0.88f,
                                    vx = (Math.random() * 0.02 - 0.01).toFloat(),
                                    vy = -0.005f - (Math.random() * 0.01).toFloat(),
                                    color = Color(0xFFE5D3B3),
                                    size = (4 + Math.random() * 6).toFloat(),
                                    lifeDecay = 0.04f
                                )
                            )
                        }
                    }
                }
                
                // Tick particles and floating text list
                val particleIterator = particles.iterator()
                while (particleIterator.hasNext()) {
                    val p = particleIterator.next()
                    p.alpha -= p.lifeDecay
                    if (p.alpha <= 0f) {
                        particleIterator.remove()
                    } else {
                        // Apply movement
                        // The coordinate x is in factor [-0.5, 0.5] from center
                        // y coordinates are in factor [0, 1] relative to height
                        val visualP = p.copy(
                            x = p.x + p.vx,
                            y = p.y + p.vy,
                            alpha = p.alpha
                        )
                        // Mutate in-place
                        p.alpha = visualP.alpha
                    }
                }
                
                // Tick popup texts
                val popupIterator = popups.iterator()
                while (popupIterator.hasNext()) {
                    val p = popupIterator.next()
                    p.yOffset -= 2.5f
                    p.alpha -= 0.02f
                    if (p.alpha <= 0f) {
                        popupIterator.remove()
                    }
                }

                // Add ground running trail particles
                if (!isJumping && gameState == GameState.PLAYING) {
                    if (Math.random() < 0.15 + (gameSpeed * 2)) {
                        val footX = playerHorizontalOffset * 0.4f + (Math.random() * 0.03 - 0.015).toFloat()
                        particles.add(
                            GameParticle(
                                x = footX,
                                y = 0.87f,
                                vx = (Math.random() * 0.005 - 0.0025).toFloat(),
                                vy = -0.001f - (Math.random() * 0.004).toFloat(),
                                color = Color(0x99DDD1BC),
                                size = (5 + Math.random() * 5).toFloat(),
                                lifeDecay = 0.03f
                            )
                        )
                    }
                }

                kotlinx.coroutines.delay(16) // tick rate targeting ~60fps
            }
        }
    }

    // Adaptive design: Game viewport restricted to a maximum optimized width
    Box(
        modifier = modifier
            .background(Color(0xFF87CEEB)) // Sky Blue fallback
            .pointerInput(gameState) {
                if (gameState == GameState.PLAYING) {
                    detectDragGestures(
                        onDragStart = {
                            accumulatedDragX = 0f
                            accumulatedDragY = 0f
                            gestureRegistered = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (!gestureRegistered) {
                                accumulatedDragX += dragAmount.x
                                accumulatedDragY += dragAmount.y
                                
                                val triggerLimit = 65f
                                if (abs(accumulatedDragX) > triggerLimit && abs(accumulatedDragX) > abs(accumulatedDragY)) {
                                    if (accumulatedDragX > 0) {
                                        // Move Right
                                        if (currentLane == Lane.CENTER) currentLane = Lane.RIGHT
                                        else if (currentLane == Lane.LEFT) currentLane = Lane.CENTER
                                    } else {
                                        // Move Left
                                        if (currentLane == Lane.CENTER) currentLane = Lane.LEFT
                                        else if (currentLane == Lane.RIGHT) currentLane = Lane.CENTER
                                    }
                                    gestureRegistered = true
                                } else if (abs(accumulatedDragY) > triggerLimit && abs(accumulatedDragY) > abs(accumulatedDragX)) {
                                    if (accumulatedDragY < 0 && !isJumping) {
                                        // Jump (Swipe Up)
                                        isJumping = true
                                        jumpVelocity = jumpPower
                                    }
                                    gestureRegistered = true
                                }
                            }
                        },
                        onDragEnd = {
                            gestureRegistered = false
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Main Game Area, capped to 500.dp to avoid wide screens looking stretched (Adaptive Canonical design)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 500.dp)
                .background(Color(0xFF556B2F)) // Forest green grass area surrounding the track
                .testTag("game_viewport_container")
        ) {
            // Render the complex 2D Parallax Graphics Layer
            GameRendererCanvas(
                playerHorizontalOffset = playerHorizontalOffset,
                jumpYOffset = jumpYOffset,
                isJumping = isJumping,
                runAnimationCycle = runAnimationCycle,
                obstacles = obstacles,
                coins = coins,
                powerUps = powerUpsItems,
                particles = particles,
                popups = popups,
                shieldDurationLeft = shieldDurationLeft,
                magnetDurationLeft = magnetDurationLeft,
                roadDecorationScroll = roadDecorationScroll,
                gameState = gameState
            )

            // Overlaid Game HUD (Active Playing details)
            if (gameState == GameState.PLAYING) {
                PlayHUD(
                    score = score,
                    coinsCollected = coinsCollected,
                    shieldDurationLeft = shieldDurationLeft,
                    magnetDurationLeft = magnetDurationLeft,
                    onMoveLeft = {
                        if (currentLane == Lane.CENTER) currentLane = Lane.LEFT
                        else if (currentLane == Lane.RIGHT) currentLane = Lane.CENTER
                    },
                    onMoveRight = {
                        if (currentLane == Lane.CENTER) currentLane = Lane.RIGHT
                        else if (currentLane == Lane.LEFT) currentLane = Lane.CENTER
                    },
                    onTriggerJump = {
                        if (!isJumping) {
                            isJumping = true; jumpVelocity = jumpPower
                        }
                    }
                )
            }

            // Start screen Overlay
            if (gameState == GameState.START_SCREEN) {
                StartScreenOverlay(
                    highScore = highScore,
                    onStartClick = {
                        resetGame()
                        gameState = GameState.PLAYING
                    }
                )
            }

            // Game over screen Overlay
            if (gameState == GameState.GAME_OVER) {
                GameOverScreenOverlay(
                    score = score.toInt(),
                    coins = coinsCollected,
                    highScore = highScore.toInt(),
                    onRestartClick = {
                        resetGame()
                        gameState = GameState.PLAYING
                    }
                )
            }
        }
    }
}

// ==========================================
// GAMEPLAY RENDERING AND PHYSICAL SIMULATION
// ==========================================

fun playFrameTick(
    obstacles: MutableList<Obstacle>,
    coins: MutableList<Coin>,
    powerUpsItems: MutableList<PowerUpItem>,
    particles: MutableList<GameParticle>,
    popups: MutableList<ScorePopup>,
    currentLane: Lane,
    playerHorizontalOffset: Float,
    isJumping: Boolean,
    jumpYOffset: Float,
    shieldDuration: Float,
    magnetDuration: Float,
    gameSpeed: Float,
    onScoreTick: () -> Unit,
    onCoinsTick: () -> Unit,
    onShieldBreak: () -> Unit,
    onCrash: () -> Unit,
    onReward: (Float) -> Unit,
    onShieldCollect: () -> Unit,
    onMagnetCollect: () -> Unit,
    onSpawnEntities: (Long) -> Unit,
    updatePlayerAnimation: () -> Unit,
    updateShieldDuration: () -> Unit,
    updateMagnetDuration: () -> Unit,
    updateGameSpeed: () -> Unit
) {
    onSpawnEntities(System.currentTimeMillis())
    onScoreTick()
    updatePlayerAnimation()
    updateShieldDuration()
    updateMagnetDuration()
    updateGameSpeed()

    // The player's collision bounds
    // Horizontal space centers at playerHorizontalOffset * 0.4f (from visual mapping center)
    val playerX = playerHorizontalOffset * 0.4f
    val playerGroundHeight = 0.85f
    // Total vertical offset from jumpYOffset is scaled to screen coordinates (~0.12f max)
    val playerHeightOffset = jumpYOffset / 1000f // jumpYOffset is e.g. -150 to 0
    val playerCurrentY = playerGroundHeight + playerHeightOffset
    
    // 1. Process Obstacles
    val obstacleIterator = obstacles.iterator()
    while (obstacleIterator.hasNext()) {
        val obs = obstacleIterator.next()
        obs.progress += gameSpeed
        
        // Remove off-screen obstacles
        if (obs.progress > 1.1f) {
            obstacleIterator.remove()
            continue
        }
        
        // Collsion check zone: around progress 0.80f to 0.90f (where player runs)
        if (obs.progress >= 0.81f && obs.progress <= 0.90f && !obs.isNetted) {
            // Get horizontal center of this obstacle's lane
            val obsX = when(obs.lane) {
                Lane.LEFT -> -0.4f
                Lane.CENTER -> 0f
                Lane.RIGHT -> 0.4f
            }
            
            // Check closeness
            if (abs(playerX - obsX) < 0.22f) {
                // Horizontal match! Check obstacle type constraints
                when (obs.type) {
                    ObstacleType.LOW_HURDLE -> {
                        // Athlete can jump over low hurdles
                        // playerHeightOffset is negative when player is in air
                        if (playerHeightOffset < -0.05f) {
                            // Safely jumped over! Give special hurdle jump bonus!
                            obs.isNetted = true
                            onReward(50f)
                            popups.add(ScorePopup("+50 JUMP BONUS!", obs.lane, obs.progress, color = Color(0xFF00FF00)))
                        } else {
                            // Crashed!
                            obs.isNetted = true
                            if (shieldDuration > 0) {
                                onShieldBreak()
                            } else {
                                onCrash()
                                return
                            }
                        }
                    }
                    ObstacleType.TALL_WALL -> {
                        // Cannot jump over tall wall. It's a heavy solid obstacle
                        obs.isNetted = true
                        if (shieldDuration > 0) {
                            onShieldBreak()
                        } else {
                            onCrash()
                            return
                        }
                    }
                }
            }
        }
    }

    // 2. Process Coins
    val coinIterator = coins.iterator()
    while (coinIterator.hasNext()) {
        val coin = coinIterator.next()
        coin.progress += gameSpeed
        
        // Magnet pulling logic
        if (magnetDuration > 0f && coin.progress > 0.35f && !coin.isCollected) {
            // Pull gradually toward player lane
            val targetLaneIndex = currentLane.index
            val coinLaneIndex = coin.lane.index
            if (coinLaneIndex != targetLaneIndex) {
                // Spawn sparkling pulling particles!
                if (Math.random() < 0.15) {
                    val coinLaneX = when(coin.lane) {
                        Lane.LEFT -> -0.4f
                        Lane.CENTER -> 0f
                        Lane.RIGHT -> 0.4f
                    }
                    particles.add(
                        GameParticle(
                            x = coinLaneX * coin.progress,
                            y = 0.3f + coin.progress * 0.55f,
                            vx = (playerX - coinLaneX) * 0.05f,
                            vy = (0.83f - coin.progress) * 0.05f,
                            color = Color(0xFFFFD700),
                            size = 5f,
                            lifeDecay = 0.05f
                        )
                    )
                }
                
                // Jump lanes instantly or slide? Let's bypass visual mapping to direct lane match
                // Coins are snapped to teammate lane for easy collection
                if (Math.random() < 0.08) {
                    // Pull closer index
                    val newIndex = if (coinLaneIndex < targetLaneIndex) coinLaneIndex + 1 else coinLaneIndex - 1
                    val newLane = Lane.entries[newIndex]
                    // Mutate
                    val indexInOriginalList = coins.indexOf(coin)
                    if (indexInOriginalList != -1) {
                        coins[indexInOriginalList] = coin.copy(lane = newLane)
                    }
                }
            }
        }

        if (coin.progress > 1.1f) {
            coinIterator.remove()
            continue
        }

        // Coin collection check
        if (coin.progress >= 0.81f && coin.progress <= 0.91f && !coin.isCollected) {
            val coinX = when(coin.lane) {
                Lane.LEFT -> -0.4f
                Lane.CENTER -> 0f
                Lane.RIGHT -> 0.4f
            }
            if (abs(playerX - coinX) < 0.22f) {
                // Height matching:
                val isHeightMatch = when (coin.type) {
                    CoinType.GROUND -> playerHeightOffset >= -0.05f // Pick up on floor or very low jump
                    CoinType.AIR -> playerHeightOffset < -0.04f  // Pick up only in jumping flight
                }
                
                if (isHeightMatch) {
                    coin.isCollected = true
                    onCoinsTick()
                    onReward(20f)
                    
                    // Spawn collection stars
                    repeat(8) {
                        particles.add(
                            GameParticle(
                                x = coinX * coin.progress,
                                y = 0.3f + coin.progress * 0.55f + (if (coin.type == CoinType.AIR) -0.06f else 0f),
                                vx = (Math.random() * 0.02 - 0.01).toFloat(),
                                vy = (Math.random() * 0.02 - 0.01).toFloat(),
                                color = Color(0xFFFFDF00),
                                size = (6 + Math.random() * 8).toFloat(),
                                lifeDecay = 0.04f
                            )
                        )
                    }
                    popups.add(ScorePopup("+20 COIN!", coin.lane, coin.progress, color = Color(0xFFFFD700)))
                    coinIterator.remove()
                }
            }
        }
    }

    // 3. Process Power-ups
    val powerUpIterator = powerUpsItems.iterator()
    while (powerUpIterator.hasNext()) {
        val item = powerUpIterator.next()
        item.progress += gameSpeed
        
        if (item.progress > 1.1f) {
            powerUpIterator.remove()
            continue
        }

        if (item.progress >= 0.81f && item.progress <= 0.91f && !item.isCollected) {
            val itemX = when(item.lane) {
                Lane.LEFT -> -0.4f
                Lane.CENTER -> 0f
                Lane.RIGHT -> 0.4f
            }
            if (abs(playerX - itemX) < 0.22f) {
                item.isCollected = true
                if (item.type == PowerUpType.SHIELD) {
                    onShieldCollect()
                    popups.add(ScorePopup("SHIELD ACTIVATED!", item.lane, item.progress, color = Color(0xFF00FFEF)))
                } else {
                    onMagnetCollect()
                    popups.add(ScorePopup("COIN MAGNET!", item.lane, item.progress, color = Color(0xFFFF3E96)))
                }
                
                // Spawn colorful particles on pick-up
                val flashColor = if (item.type == PowerUpType.SHIELD) Color(0xFF00FFFF) else Color(0xFFFF1493)
                repeat(15) {
                    particles.add(
                        GameParticle(
                            x = itemX * item.progress,
                            y = 0.3f + item.progress * 0.55f,
                            vx = (Math.random() * 0.02 - 0.01).toFloat(),
                            vy = (Math.random() * 0.025 - 0.015).toFloat(),
                            color = flashColor,
                            size = (8 + Math.random() * 10).toFloat(),
                            lifeDecay = 0.03f
                        )
                    )
                }
                powerUpIterator.remove()
            }
        }
    }
}

// Spawner configurations
private fun spawnRandomObstaclesAndCoins(
    obstacles: MutableList<Obstacle>,
    coins: MutableList<Coin>,
    powerUps: MutableList<PowerUpItem>
) {
    val random = Random()
    val patternType = random.nextInt(6) // Patterns of entities
    
    // Choose which lanes will have items (always leave at least one lane clear/jumpable to ensure fairness!)
    when (patternType) {
        0 -> { // 2 obstacles (1 jumps, 1 slide)
            obstacles.add(Obstacle(lane = Lane.LEFT, type = ObstacleType.TALL_WALL))
            obstacles.add(Obstacle(lane = Lane.CENTER, type = ObstacleType.LOW_HURDLE))
            // Coins on Right (AIR)
            coins.add(Coin(lane = Lane.RIGHT, type = CoinType.AIR))
            if (random.nextFloat() < 0.2f) {
                powerUps.add(PowerUpItem(lane = Lane.RIGHT, type = PowerUpType.MAGNET))
            }
        }
        1 -> { // Hurdles galore
            obstacles.add(Obstacle(lane = Lane.LEFT, type = ObstacleType.LOW_HURDLE))
            obstacles.add(Obstacle(lane = Lane.RIGHT, type = ObstacleType.LOW_HURDLE))
            coins.add(Coin(lane = Lane.CENTER, type = CoinType.GROUND))
            coins.add(Coin(lane = Lane.CENTER, type = CoinType.AIR, progress = -0.1f)) // Staggered air coin
        }
        2 -> { // Single heavy wall and gold coin row
            obstacles.add(Obstacle(lane = Lane.CENTER, type = ObstacleType.TALL_WALL))
            coins.add(Coin(lane = Lane.LEFT, type = CoinType.GROUND))
            coins.add(Coin(lane = Lane.RIGHT, type = CoinType.GROUND))
            if (random.nextFloat() < 0.25f) {
                powerUps.add(PowerUpItem(lane = Lane.LEFT, type = PowerUpType.SHIELD))
            }
        }
        3 -> { // Clean line of coins
            coins.add(Coin(lane = Lane.LEFT, type = CoinType.GROUND))
            coins.add(Coin(lane = Lane.CENTER, type = CoinType.GROUND))
            coins.add(Coin(lane = Lane.RIGHT, type = CoinType.GROUND))
        }
        4 -> { // High jump training
            obstacles.add(Obstacle(lane = Lane.CENTER, type = ObstacleType.LOW_HURDLE))
            coins.add(Coin(lane = Lane.CENTER, type = CoinType.AIR, progress = -0.05f))
        }
        5 -> { // Block the side channels, center is clear
            obstacles.add(Obstacle(lane = Lane.LEFT, type = ObstacleType.TALL_WALL))
            obstacles.add(Obstacle(lane = Lane.RIGHT, type = ObstacleType.TALL_WALL))
            coins.add(Coin(lane = Lane.CENTER, type = CoinType.GROUND))
            coins.add(Coin(lane = Lane.CENTER, type = CoinType.GROUND, progress = -0.15f))
        }
    }
}

// ==========================================
// CUSTOM JETPACK COMPOSE DRAWING WORKFLOW
// ==========================================

@Composable
fun GameRendererCanvas(
    playerHorizontalOffset: Float,
    jumpYOffset: Float,
    isJumping: Boolean,
    runAnimationCycle: Float,
    obstacles: List<Obstacle>,
    coins: List<Coin>,
    powerUps: List<PowerUpItem>,
    particles: List<GameParticle>,
    popups: List<ScorePopup>,
    shieldDurationLeft: Float,
    magnetDurationLeft: Float,
    roadDecorationScroll: Float,
    gameState: GameState,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("game_rendering_canvas")
    ) {
        val width = size.width
        val height = size.height
        val horizonY = height * 0.35f // The depth horizon where lanes converge in pseudo-3D

        // 1. Draw Sky Gradient
        drawSky(width, horizonY)

        // 2. Draw Scenic Mountains & Sun in Background
        drawBackgroundEnvironment(width, horizonY, roadDecorationScroll)

        // 3. Draw Green Grass Fields Bottom Ground
        drawRect(
            color = Color(0xFF4CAF50),
            topLeft = Offset(0f, horizonY),
            size = Size(width, height - horizonY)
        )

        // 4. Draw Receding 3D Highways / Roads
        draw3DRoad(width, height, horizonY, roadDecorationScroll)

        // 5. Draw Decorative Ground elements (Scattered wild flowers / street poles scrolling)
        drawFieldEnvironment(width, height, horizonY, roadDecorationScroll)

        // 6. Draw Scattered Gold Coins
        coins.forEach { coin ->
            if (!coin.isCollected) {
                draw3DCoin(coin, width, height, horizonY)
            }
        }

        // 7. Draw Power-up Items
        powerUps.forEach { item ->
            if (!item.isCollected) {
                draw3DPowerUp(item, width, height, horizonY)
            }
        }

        // 8. Draw Dangerous Hurdles and Solid Walls
        obstacles.forEach { obs ->
            if (!obs.isNetted) {
                draw3DObstacle(obs, width, height, horizonY)
            }
        }

        // 9. Draw Particle Trail / Sparks
        particles.forEach { p ->
            // p.x is in range [-0.5, 0.5]. Map relative to screen width with horizon conversion
            val visualX = (width / 2f) + (p.x * width * p.y)
            val visualY = horizonY + (p.y - 0.35f) * (height - horizonY) / 0.65f
            drawCircle(
                color = p.color.copy(alpha = p.alpha),
                radius = p.size * (0.3f + p.y * 0.7f),
                center = Offset(visualX, visualY)
            )
        }

        // 10. Draw Athlete Character Runner
        if (gameState != GameState.START_SCREEN) {
            drawRunningAthlete(
                width = width,
                height = height,
                horizonY = horizonY,
                laneOffset = playerHorizontalOffset,
                jumpY = jumpYOffset,
                isJumping = isJumping,
                runCycle = runAnimationCycle,
                isCrashed = gameState == GameState.GAME_OVER,
                shieldActive = shieldDurationLeft > 0f,
                magnetActive = magnetDurationLeft > 0f
            )
        }

        // 11. Draw Score floating popup notifications
        popups.forEach { pop ->
            val popX = when (pop.lane) {
                Lane.LEFT -> -0.4f
                Lane.CENTER -> 0f
                Lane.RIGHT -> 0.4f
            }
            val visualX = (width / 2f) + (popX * width * pop.progress)
            val baseVisualY = horizonY + (pop.progress) * (height - horizonY)
            val finalY = baseVisualY + pop.yOffset
            
            // Draw text safely inside limits
            if (finalY > 100f && visualX > 0f && visualX < width) {
                // Approximate a beautiful Canvas text representation or tiny badges
                // Drawing text in Android Canvas can be done via drawContext.canvas.nativeCanvas, 
                // but since we want highly-reliable multiplatform, we draw neat styled visual indicators:
                drawRoundRect(
                    color = pop.color.copy(alpha = pop.alpha),
                    topLeft = Offset(visualX - 60f, finalY - 20f),
                    size = Size(120f, 40f),
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = 3f)
                )
                // Draw a small bright visual indicator star
                drawCircle(
                    color = pop.color.copy(alpha = pop.alpha),
                    radius = 6f,
                    center = Offset(visualX, finalY)
                )
            }
        }
    }
}

// ==========================================
// PROCEDURAL CANVAS ELEMENT DESIGNS
// ==========================================

private fun DrawScope.drawSky(width: Float, horizonY: Float) {
    val skyGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF03010C), // Cosmic black-blue
            Color(0xFF130122), // Midnight purple
            Color(0xFF38002C), // Deep magenta
            Color(0xFF880050)  // Neon pink-red horizon glow
        ),
        startY = 0f,
        endY = horizonY
    )
    drawRect(brush = skyGradient, size = Size(width, horizonY))
    
    // Draw grid of stars in the background sky
    val starCount = 30
    for (i in 0 until starCount) {
        // Pseudo-random star placement based on index
        val starX = ((i * 1234.567f) % width)
        val starY = ((i * 9876.543f) % (horizonY - 20f))
        val twinkle = 0.4f + 0.6f * sin((System.currentTimeMillis() / 400f) + i)
        
        drawCircle(
            color = Color(0xFFD7F0FF).copy(alpha = 0.3f * twinkle),
            radius = 1.5f + (i % 2).toFloat() * 1.5f,
            center = Offset(starX, starY)
        )
        
        // Occasional larger star with flare
        if (i % 8 == 0) {
            drawLine(
                color = Color.White.copy(alpha = 0.5f * twinkle),
                start = Offset(starX - 4f, starY),
                end = Offset(starX + 4f, starY),
                strokeWidth = 1f
            )
            drawLine(
                color = Color.White.copy(alpha = 0.5f * twinkle),
                start = Offset(starX, starY - 4f),
                end = Offset(starX, starY + 4f),
                strokeWidth = 1f
            )
        }
    }
}

private fun DrawScope.drawBackgroundEnvironment(width: Float, horizonY: Float, scroll: Float) {
    // 1. Draw Massive Sliced Cyber Retro Sun (Synthwave classic theme)
    val sunRadius = 140f
    val sunCenterX = width * 0.5f
    val sunCenterY = horizonY - 15f
    
    val sunBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFFFFF33), // Golden bright yellow
            Color(0xFFFF5E00), // Vibrant neon orange
            Color(0xFFFF0055)  // Hot magenta pink at bottom
        ),
        startY = sunCenterY - sunRadius,
        endY = sunCenterY + sunRadius
    )
    
    // Big base glowing sun
    drawCircle(
        brush = sunBrush,
        radius = sunRadius,
        center = Offset(sunCenterX, sunCenterY)
    )
    
    // Sun scanline slots (thickening towards the bottom)
    val startSliceY = sunCenterY - sunRadius * 0.3f
    val endSliceY = sunCenterY + sunRadius
    var sliceY = startSliceY
    var sliceHeight = 4f
    var gapHeight = 14f
    
    while (sliceY < endSliceY) {
        drawRect(
            color = Color(0xFF130122).copy(alpha = 0.85f), // blend with sky background
            topLeft = Offset(sunCenterX - sunRadius, sliceY),
            size = Size(sunRadius * 2f, sliceHeight)
        )
        sliceY += sliceHeight + gapHeight
        sliceHeight += 2f // Thickening lines
        gapHeight = maxOf(4f, gapHeight - 1.2f) // Thinner gaps
    }

    // 2. Neon Grid Horizon Lines fading into mist
    val gridsCount = 8
    for (i in 0 until gridsCount) {
        val yPos = horizonY - (i * i * 3f)
        val alphaVal = 0.35f * (1f - (i.toFloat() / gridsCount))
        drawLine(
            color = Color(0xFF00FFFF).copy(alpha = alphaVal), // Neon cyan
            start = Offset(0f, yPos),
            end = Offset(width, yPos),
            strokeWidth = 1f
        )
    }

    // 3. Layered Cyber-run Mountains outline glow
    // Distant dark mountain peaks
    val distMountainPath = Path().apply {
        moveTo(0f, horizonY)
        lineTo(width * 0.12f, horizonY - 55f)
        lineTo(width * 0.28f, horizonY - 25f)
        lineTo(width * 0.45f, horizonY - 95f)
        lineTo(width * 0.65f, horizonY - 40f)
        lineTo(width * 0.85f, horizonY - 80f)
        lineTo(width, horizonY)
        close()
    }
    // Deep silhouette fill
    drawPath(distMountainPath, color = Color(0xFF13072C))
    
    // Distant mountain glowing top edge
    drawPath(distMountainPath, color = Color(0xFFFF00FF).copy(alpha = 0.4f), style = Stroke(width = 2.5f))

    // Near mountain peaks
    val nearMountainPath = Path().apply {
        moveTo(0f, horizonY)
        lineTo(width * 0.22f, horizonY - 35f)
        lineTo(width * 0.52f, horizonY - 70f)
        lineTo(width * 0.72f, horizonY - 25f)
        lineTo(width * 0.90f, horizonY - 50f)
        lineTo(width, horizonY)
        close()
    }
    drawPath(nearMountainPath, color = Color(0xFF0E031E))
    // Vibrant cyan outline glow
    drawPath(nearMountainPath, color = Color(0xFF00FFFF).copy(alpha = 0.6f), style = Stroke(width = 3f))
}

private fun DrawScope.draw3DRoad(width: Float, height: Float, horizonY: Float, scroll: Float) {
    val segmentsCount = 10
    val segmentLength = 1f / segmentsCount
    val offset = (scroll / 100f) % segmentLength
    
    val centerX = width / 2f
    
    for (k in 0..segmentsCount + 1) {
        val progStart = -offset + (k * segmentLength)
        val progEnd = progStart + segmentLength
        
        val pStartClamped = maxOf(0f, minOf(1f, progStart))
        val pEndClamped = maxOf(0f, minOf(1f, progEnd))
        
        if (pStartClamped < pEndClamped) {
            val pStart = pStartClamped * pStartClamped
            val pEnd = pEndClamped * pEndClamped
            
            val yTop = horizonY + pStart * (height - horizonY)
            val yBottom = horizonY + pEnd * (height - horizonY)
            
            // Road width at top and bottom of this segment
            val roadWidthTop = width * 0.16f + pStart * (width * 0.65f)
            val roadWidthBottom = width * 0.16f + pEnd * (width * 0.65f)
            
            // Draw Cosmic fields on the sides first (behind the road)
            val cosmicColor = if (k % 2 == 0) Color(0xFF0F0623) else Color(0xFF150931) // Retro dark purple fields
            
            // Left field
            val leftFieldPath = Path().apply {
                moveTo(0f, yTop)
                lineTo(centerX - roadWidthTop / 2f, yTop)
                lineTo(centerX - roadWidthBottom / 2f, yBottom)
                lineTo(0f, yBottom)
                close()
            }
            drawPath(leftFieldPath, color = cosmicColor)
            
            // Right field
            val rightFieldPath = Path().apply {
                moveTo(centerX + roadWidthTop / 2f, yTop)
                lineTo(width, yTop)
                lineTo(width, yBottom)
                lineTo(centerX + roadWidthBottom / 2f, yBottom)
                close()
            }
            drawPath(rightFieldPath, color = cosmicColor)
            
            // Draw Cyber Asphalt road segment
            val roadColor = if (k % 2 == 0) Color(0xFF07050E) else Color(0xFF0C0917) // Near-black violet asphalt
            val roadSegPath = Path().apply {
                moveTo(centerX - roadWidthTop / 2f, yTop)
                lineTo(centerX + roadWidthTop / 2f, yTop)
                lineTo(centerX + roadWidthBottom / 2f, yBottom)
                lineTo(centerX - roadWidthBottom / 2f, yBottom)
                close()
            }
            drawPath(roadSegPath, color = roadColor)
            
            // Side curbs (shoulder lines / racing stripes) on road edges
            val curbWidthTop = 10f * (0.12f + pStart * 1.15f)
            val curbWidthBottom = 10f * (0.12f + pEnd * 1.15f)
            
            // Alternating bright neon pink & neon cyan
            val curbColor = if (k % 2 == 0) Color(0xFFFF007F) else Color(0xFF00FFCC)
            
            // Left Curb
            val leftCurbPath = Path().apply {
                moveTo(centerX - roadWidthTop / 2f - curbWidthTop, yTop)
                lineTo(centerX - roadWidthTop / 2f, yTop)
                lineTo(centerX - roadWidthBottom / 2f, yBottom)
                lineTo(centerX - roadWidthBottom / 2f - curbWidthBottom, yBottom)
                close()
            }
            drawPath(leftCurbPath, color = curbColor)
            
            // Right Curb
            val rightCurbPath = Path().apply {
                moveTo(centerX + roadWidthTop / 2f, yTop)
                lineTo(centerX + roadWidthTop / 2f + curbWidthTop, yTop)
                lineTo(centerX + roadWidthBottom / 2f + curbWidthBottom, yBottom)
                lineTo(centerX + roadWidthBottom / 2f, yBottom)
                close()
            }
            drawPath(rightCurbPath, color = curbColor)
            
            // Lane Dividers (Dashed lines halfway across lanes)
            if (k % 2 == 0) {
                val laneWidthTop = roadWidthTop / 3f
                val laneWidthBottom = roadWidthBottom / 3f
                
                val dividerXLeftTop = centerX - 0.55f * laneWidthTop
                val dividerXLeftBottom = centerX - 0.55f * laneWidthBottom
                
                val dividerXRightTop = centerX + 0.55f * laneWidthTop
                val dividerXRightBottom = centerX + 0.55f * laneWidthBottom
                
                val strokeW = 2.5f + pEnd * 7f
                
                drawLine(
                    color = Color(0xFFFFFF33), // Sharp retro laser-yellow
                    start = Offset(dividerXLeftTop, yTop),
                    end = Offset(dividerXLeftBottom, yBottom),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color(0xFFFFFF33),
                    start = Offset(dividerXRightTop, yTop),
                    end = Offset(dividerXRightBottom, yBottom),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private fun DrawScope.drawFieldEnvironment(width: Float, height: Float, horizonY: Float, scroll: Float) {
    // Draw spectacular futuristic neon light pillars/pylons scrolling down the side banks
    val entitiesCount = 4
    for (i in 0 until entitiesCount) {
        val relativeProgress = ((i.toFloat() / entitiesCount) + (scroll / 100f)) % 1f
        val p = relativeProgress * relativeProgress
        val y = horizonY + p * (height - horizonY)
        
        val pylonHeight = 30f + p * 180f
        val pylonWidth = 3f + p * 14f
        val lightRadius = 6f + p * 18f

        // Side offsets matching the scenic road perspective spread
        val leftPylonX = width * 0.15f - (p * width * 0.28f)
        val rightPylonX = width * 0.85f + (p * width * 0.28f)

        // 1. DRAW LEFT CYBER PYLON (Sleek dark violet pillar with neon magenta beacon)
        drawRoundRect(
            color = Color(0xFF140B24),
            topLeft = Offset(leftPylonX - pylonWidth * 0.5f, y - pylonHeight),
            size = Size(pylonWidth, pylonHeight),
            cornerRadius = CornerRadius(2f + p * 5f, 2f + p * 5f)
        )
        // High-contrast reflective panel line
        drawLine(
            color = Color(0xFFFF007F).copy(alpha = 0.5f),
            start = Offset(leftPylonX, y - pylonHeight * 0.8f),
            end = Offset(leftPylonX, y),
            strokeWidth = 1f + p * 2f
        )
        // Beacon base bracket
        drawRect(
            color = Color(0xFF2E1C4E),
            topLeft = Offset(leftPylonX - pylonWidth * 0.8f, y - pylonHeight - pylonWidth * 0.5f),
            size = Size(pylonWidth * 1.6f, pylonWidth * 0.6f)
        )
        // Radiant glowing flare around the top lamp
        val leftPulse = 1f + 0.25f * sin((System.currentTimeMillis() / 200f) + i)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xBBFF0055), Color(0x33FF0055), Color(0x00FF0055)),
                center = Offset(leftPylonX, y - pylonHeight - pylonWidth * 0.3f),
                radius = lightRadius * 3.5f * leftPulse
            ),
            radius = lightRadius * 3.5f * leftPulse,
            center = Offset(leftPylonX, y - pylonHeight - pylonWidth * 0.3f)
        )
        // Bright solid hot pink core bulb
        drawCircle(
            color = Color(0xFFFF45A4),
            radius = lightRadius,
            center = Offset(leftPylonX, y - pylonHeight - pylonWidth * 0.3f)
        )

        // 2. DRAW RIGHT CYBER PYLON (Sleek dark violet pillar with neon cyan beacon)
        drawRoundRect(
            color = Color(0xFF140B24),
            topLeft = Offset(rightPylonX - pylonWidth * 0.5f, y - pylonHeight),
            size = Size(pylonWidth, pylonHeight),
            cornerRadius = CornerRadius(2f + p * 5f, 2f + p * 5f)
        )
        // High-contrast reflective panel line
        drawLine(
            color = Color(0xFF00FFFF).copy(alpha = 0.5f),
            start = Offset(rightPylonX, y - pylonHeight * 0.8f),
            end = Offset(rightPylonX, y),
            strokeWidth = 1f + p * 2f
        )
        // Beacon base bracket
        drawRect(
            color = Color(0xFF2E1C4E),
            topLeft = Offset(rightPylonX - pylonWidth * 0.8f, y - pylonHeight - pylonWidth * 0.5f),
            size = Size(pylonWidth * 1.6f, pylonWidth * 0.6f)
        )
        // Radiant glowing flare around the top lamp
        val rightPulse = 1f + 0.25f * cos((System.currentTimeMillis() / 200f) + i)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xBB00FFFF), Color(0x3300FFFF), Color(0x0000FFFF)),
                center = Offset(rightPylonX, y - pylonHeight - pylonWidth * 0.3f),
                radius = lightRadius * 3.5f * rightPulse
            ),
            radius = lightRadius * 3.5f * rightPulse,
            center = Offset(rightPylonX, y - pylonHeight - pylonWidth * 0.3f)
        )
        // Bright solid cyan core bulb
        drawCircle(
            color = Color(0xFFE0FFFF),
            radius = lightRadius,
            center = Offset(rightPylonX, y - pylonHeight - pylonWidth * 0.3f)
        )
    }
}

private fun DrawScope.draw3DObstacle(obs: Obstacle, width: Float, height: Float, horizonY: Float) {
    val p = obs.progress * obs.progress
    val y = horizonY + p * (height - horizonY)
    
    val centerX = width / 2f
    val laneMult = when(obs.lane) {
        Lane.LEFT -> -1f
        Lane.CENTER -> 0f
        Lane.RIGHT -> 1f
    }
    
    // Scale road width relative expansion factor
    val roadWidthAtY = width * 0.16f + p * (width * 0.65f)
    val laneWidthAtY = roadWidthAtY / 3f
    val obsX = centerX + laneMult * (laneWidthAtY * 1.1f)

    val scaleFactor = 0.12f + p * 1.15f
    val obstacleWidth = 32.dp.toPx() * scaleFactor
    
    if (y < height) {
        val isLightFlash = (System.currentTimeMillis() / 250) % 2 == 0L
        
        when(obs.type) {
            ObstacleType.LOW_HURDLE -> {
                // REDEFINED: Dynamic construction worker style safety hurdle
                val hHeight = 35.dp.toPx() * scaleFactor
                val boardTopY = y - hHeight
                val boardHeight = 12.dp.toPx() * scaleFactor
                
                // Draw sturdy A-frame metal base support feet
                // Left leg
                drawLine(
                    color = Color(0xFF333333),
                    start = Offset(obsX - obstacleWidth * 0.45f, y),
                    end = Offset(obsX - obstacleWidth * 0.35f, boardTopY),
                    strokeWidth = 4f * scaleFactor,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color(0xFF555555),
                    start = Offset(obsX - obstacleWidth * 0.35f, y),
                    end = Offset(obsX - obstacleWidth * 0.35f, boardTopY),
                    strokeWidth = 2f * scaleFactor
                )
                // Right leg
                drawLine(
                    color = Color(0xFF333333),
                    start = Offset(obsX + obstacleWidth * 0.45f, y),
                    end = Offset(obsX + obstacleWidth * 0.35f, boardTopY),
                    strokeWidth = 4f * scaleFactor,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color(0xFF555555),
                    start = Offset(obsX + obstacleWidth * 0.35f, y),
                    end = Offset(obsX + obstacleWidth * 0.35f, boardTopY),
                    strokeWidth = 2f * scaleFactor
                )
                
                // Main caution horizontal gate board (Orange)
                drawRoundRect(
                    color = Color(0xFFFF5722), // Bright Safety Orange
                    topLeft = Offset(obsX - obstacleWidth * 0.48f, boardTopY),
                    size = Size(obstacleWidth * 0.96f, boardHeight),
                    cornerRadius = CornerRadius(3.dp.toPx() * scaleFactor, 3.dp.toPx() * scaleFactor)
                )
                
                // Draw high-contrast white slanting caution stripes
                val stripesCount = 4
                val stripeWidth = (obstacleWidth * 0.96f) / stripesCount
                for (s in 0 until stripesCount) {
                    val sX = (obsX - obstacleWidth * 0.48f) + s * stripeWidth
                    if (s % 2 == 0) {
                        val stripePath = Path().apply {
                            moveTo(sX, boardTopY)
                            lineTo(sX + stripeWidth * 0.4f, boardTopY)
                            lineTo(sX + stripeWidth * 0.7f, boardTopY + boardHeight)
                            lineTo(sX + stripeWidth * 0.3f, boardTopY + boardHeight)
                            close()
                        }
                        drawPath(stripePath, color = Color(0xFFFFFFFF)) // White safety stripes
                    }
                }
                
                // Center flashing warning beacon beacon base
                val lampRadius = 6.dp.toPx() * scaleFactor
                val lampY = boardTopY - lampRadius * 0.5f
                drawRect(
                    color = Color(0xFF1A1A1A), // Dark lamp housing
                    topLeft = Offset(obsX - lampRadius * 0.8f, boardTopY - lampRadius * 1.2f),
                    size = Size(lampRadius * 1.6f, lampRadius * 1.2f)
                )
                
                // Glowing/blinking beacon bowl
                val orangeGlow = Brush.radialGradient(
                    colors = if (isLightFlash) listOf(Color(0xFFFFEA00), Color(0x33FFB300), Color(0x00FFB300))
                             else listOf(Color(0xFFFF5722), Color(0x00FF5722)),
                    center = Offset(obsX, boardTopY - lampRadius * 1.5f),
                    radius = lampRadius * (if (isLightFlash) 3.5f else 1.2f)
                )
                drawCircle(
                    color = if (isLightFlash) Color(0xFFFFD700) else Color(0xFFD84315),
                    radius = lampRadius,
                    center = Offset(obsX, boardTopY - lampRadius * 1.5f)
                )
                drawCircle(
                    brush = orangeGlow,
                    radius = lampRadius * (if (isLightFlash) 3.5f else 1.2f),
                    center = Offset(obsX, boardTopY - lampRadius * 1.5f)
                )
            }
            ObstacleType.TALL_WALL -> {
                // REDEFINED: Heavy industrial warning barricade block
                val wallHeight = 72.dp.toPx() * scaleFactor
                val wallTopY = y - wallHeight
                
                // Ambient drop shadow under the wall
                drawRoundRect(
                    color = Color(0x44000000),
                    topLeft = Offset(obsX - obstacleWidth * 0.53f, wallTopY + 5.dp.toPx() * scaleFactor),
                    size = Size(obstacleWidth * 1.06f, wallHeight),
                    cornerRadius = CornerRadius(6.dp.toPx() * scaleFactor, 6.dp.toPx() * scaleFactor)
                )

                // Main heavy concrete wall segment plate
                drawRoundRect(
                    color = Color(0xFF2C3E50), // Steel navy industrial metal / concrete composite
                    topLeft = Offset(obsX - obstacleWidth * 0.5f, wallTopY),
                    size = Size(obstacleWidth, wallHeight),
                    cornerRadius = CornerRadius(5.dp.toPx() * scaleFactor, 5.dp.toPx() * scaleFactor)
                )
                
                // Base anchors (left & right black metallic structural corner braces)
                drawRect(
                    color = Color(0xFF111111),
                    topLeft = Offset(obsX - obstacleWidth * 0.52f, y - 10.dp.toPx() * scaleFactor),
                    size = Size(8.dp.toPx() * scaleFactor, 10.dp.toPx() * scaleFactor)
                )
                drawRect(
                    color = Color(0xFF111111),
                    topLeft = Offset(obsX + obstacleWidth * 0.52f - 8.dp.toPx() * scaleFactor, y - 10.dp.toPx() * scaleFactor),
                    size = Size(8.dp.toPx() * scaleFactor, 10.dp.toPx() * scaleFactor)
                )

                // Diagonal hazard warning stripe patterns along the outline border edges (Yellow & Black)
                val barH = 10.dp.toPx() * scaleFactor
                drawRect(
                    color = Color(0xFFFEE12B), // Traffic Yellow accent top beam
                    topLeft = Offset(obsX - obstacleWidth * 0.5f, wallTopY),
                    size = Size(obstacleWidth, barH)
                )
                
                val stripeSpan = obstacleWidth / 5f
                for (s in 0..4) {
                    val sX = (obsX - obstacleWidth * 0.5f) + s * stripeSpan
                    val stripePath = Path().apply {
                        moveTo(sX, wallTopY)
                        lineTo(sX + stripeSpan * 0.5f, wallTopY)
                        lineTo(sX + stripeSpan * 0.9f, wallTopY + barH)
                        lineTo(sX + stripeSpan * 0.4f, wallTopY + barH)
                        close()
                    }
                    drawPath(stripePath, color = Color(0xFF212121)) // Black danger stripes
                }

                // Central Warning "▲" Safety Signpost with an exclamation point!
                val signSize = wallHeight * 0.44f
                val signCenterY = wallTopY + wallHeight * 0.55f
                
                val signPath = Path().apply {
                    moveTo(obsX, signCenterY - signSize * 0.55f)
                    lineTo(obsX - signSize * 0.5f, signCenterY + signSize * 0.35f)
                    lineTo(obsX + signSize * 0.5f, signCenterY + signSize * 0.35f)
                    close()
                }
                drawPath(signPath, color = Color(0xFFFEE12B)) // Yellow warning triangle plate
                
                // Draw exclamation point inside triangle
                drawLine(
                    color = Color(0xFF212121),
                    start = Offset(obsX, signCenterY - signSize * 0.2f),
                    end = Offset(obsX, signCenterY + signSize * 0.1f),
                    strokeWidth = 3f * scaleFactor,
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = Color(0xFF212121),
                    radius = 2.5f * scaleFactor,
                    center = Offset(obsX, signCenterY + signSize * 0.26f)
                )

                // Double Top-Corner Alternating Blinking Warning Dome Lights
                val leftLightFlash = isLightFlash
                val rightLightFlash = !isLightFlash
                val domeRadius = 5.dp.toPx() * scaleFactor
                
                // Left Light Dome
                val leftLightX = obsX - obstacleWidth * 0.42f
                val lightY = wallTopY - domeRadius * 0.3f
                drawCircle(
                    color = if (leftLightFlash) Color(0xFFFF3333) else Color(0xFF8B0000), // Intense Alert Red
                    radius = domeRadius,
                    center = Offset(leftLightX, lightY)
                )
                if (leftLightFlash) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFFF8888), Color(0x33FF0000), Color(0x00FF0000)),
                            center = Offset(leftLightX, lightY),
                            radius = domeRadius * 3.5f
                        ),
                        radius = domeRadius * 3.5f,
                        center = Offset(leftLightX, lightY)
                    )
                }

                // Right Light Dome
                val rightLightX = obsX + obstacleWidth * 0.42f
                drawCircle(
                    color = if (rightLightFlash) Color(0xFFFF3333) else Color(0xFF8B0000),
                    radius = domeRadius,
                    center = Offset(rightLightX, lightY)
                )
                if (rightLightFlash) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFFF8888), Color(0x33FF0000), Color(0x00FF0000)),
                            center = Offset(rightLightX, lightY),
                            radius = domeRadius * 3.5f
                        ),
                        radius = domeRadius * 3.5f,
                        center = Offset(rightLightX, lightY)
                    )
                }
            }
        }
    }
}

private fun DrawScope.draw3DCoin(coin: Coin, width: Float, height: Float, horizonY: Float) {
    val p = coin.progress * coin.progress
    val y = horizonY + p * (height - horizonY)
    
    val centerX = width / 2f
    val laneMult = when(coin.lane) {
        Lane.LEFT -> -1f
        Lane.CENTER -> 0f
        Lane.RIGHT -> 1f
    }
    
    val roadWidthAtY = width * 0.16f + p * (width * 0.65f)
    val laneWidthAtY = roadWidthAtY / 3f
    val coinX = centerX + laneMult * (laneWidthAtY * 1.1f)

    // Lift coin off road if AIR coin
    val coinHeightOffset = if (coin.type == CoinType.AIR) {
        -55.dp.toPx() * (0.3f + p * 0.7f) // lifts higher when closer
    } else {
        -5.dp.toPx()
    }
    
    val finalY = y + coinHeightOffset
    val scaleFactor = 0.15f + p * 1.2f
    val radius = 10.dp.toPx() * scaleFactor

    if (y < height) {
        // Draw Gold coin outer ring
        drawCircle(
            color = Color(0xFFFFD700), // Pure gold yellow
            radius = radius,
            center = Offset(coinX, finalY)
        )
        // Shading stroke
        drawCircle(
            color = Color(0xFFB8860B), // Dark goldenrod
            radius = radius,
            center = Offset(coinX, finalY),
            style = Stroke(width = 2.5f * scaleFactor)
        )
        // Shiny star core
        drawCircle(
            color = Color(0xFFFFFFE0), // Lemon light
            radius = radius * 0.5f,
            center = Offset(coinX, finalY)
        )
    }
}

private fun DrawScope.draw3DPowerUp(item: PowerUpItem, width: Float, height: Float, horizonY: Float) {
    val p = item.progress * item.progress
    val y = horizonY + p * (height - horizonY)
    
    val centerX = width / 2f
    val laneMult = when(item.lane) {
        Lane.LEFT -> -1f
        Lane.CENTER -> 0f
        Lane.RIGHT -> 1f
    }
    
    val roadWidthAtY = width * 0.16f + p * (width * 0.65f)
    val laneWidthAtY = roadWidthAtY / 3f
    val itemX = centerX + laneMult * (laneWidthAtY * 1.1f)
    
    val finalY = y - 12.dp.toPx() * (0.3f + p * 0.7f)
    val scaleFactor = 0.2f + p * 1.3f
    val sizeVal = 18.dp.toPx() * scaleFactor

    if (y < height) {
        when (item.type) {
            PowerUpType.SHIELD -> {
                // Circle blue orb container
                drawCircle(
                    color = Color(0xFF00E5FF),
                    radius = sizeVal * 0.7f,
                    center = Offset(itemX, finalY)
                )
                drawCircle(
                    color = Color.White,
                    radius = sizeVal * 0.6f,
                    center = Offset(itemX, finalY),
                    style = Stroke(width = 3f * scaleFactor)
                )
                // Draw a beautiful small secure shield emblem cross inside
                drawLine(
                    color = Color.White,
                    start = Offset(itemX, finalY - sizeVal * 0.35f),
                    end = Offset(itemX, finalY + sizeVal * 0.35f),
                    strokeWidth = 4f * scaleFactor
                )
                drawLine(
                    color = Color.White,
                    start = Offset(itemX - sizeVal * 0.35f, finalY),
                    end = Offset(itemX + sizeVal * 0.35f, finalY),
                    strokeWidth = 4f * scaleFactor
                )
            }
            PowerUpType.MAGNET -> {
                // Red magnet (U shape)
                drawRoundRect(
                    color = Color(0xFFFF1493), // Vivid magenta magnet base
                    topLeft = Offset(itemX - sizeVal * 0.5f, finalY - sizeVal * 0.5f),
                    size = Size(sizeVal, sizeVal),
                    cornerRadius = CornerRadius(5.dp.toPx() * scaleFactor, 5.dp.toPx() * scaleFactor)
                )
                // Hollow spacer to make it U shape inside
                drawRoundRect(
                    color = Color(0xFF4CAF50), // Matches grass or blend bg
                    topLeft = Offset(itemX - sizeVal * 0.22f, finalY - sizeVal * 0.6f),
                    size = Size(sizeVal * 0.44f, sizeVal * 0.7f),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
                // White terminal caps
                drawRect(
                    color = Color.White,
                    topLeft = Offset(itemX - sizeVal * 0.5f, finalY - sizeVal * 0.52f),
                    size = Size(sizeVal * 0.25f, sizeVal * 0.2f)
                )
                drawRect(
                    color = Color.White,
                    topLeft = Offset(itemX + sizeVal * 0.25f, finalY - sizeVal * 0.52f),
                    size = Size(sizeVal * 0.25f, sizeVal * 0.2f)
                )
            }
        }
    }
}

// Draw the dynamic athletic 2D avatar running inside lanes
private fun DrawScope.drawRunningAthlete(
    width: Float,
    height: Float,
    horizonY: Float,
    laneOffset: Float, // interpolate -1 to 1 offset
    jumpY: Float, // jump offset height off-ground negative
    isJumping: Boolean,
    runCycle: Float, // runs 0 to PI*2 cycle
    isCrashed: Boolean,
    shieldActive: Boolean,
    magnetActive: Boolean
) {
    val playerGroundY = 0.86f // Fixed baseline runner vertical position
    val baseCenterX = width / 2f
    val layoutSpread = width * 0.46f // Horizontal range of the lane slider
    val playerX = baseCenterX + (laneOffset * layoutSpread * 0.4f)
    
    // Scale jumps: progress determines coordinate size context. The athlete lives at 100% proximity bottom
    val playerY = (horizonY + (playerGroundY - 0.35f) * (height - horizonY) / 0.65f) + jumpY

    // Dimensions
    val headRadius = 14.dp.toPx()
    val torsoHeight = 35.dp.toPx()
    val hipWidth = 12.dp.toPx()

    // 1. Draw Player Ground Shadow
    // Shadow remains elements on the track floor even when athlete jumps (Subway Surfers effect)
    val shadowY = horizonY + (playerGroundY - 0.35f) * (height - horizonY) / 0.65f
    val jumpHeightRatio = maxOf(0f, 1f - abs(jumpY) / 250f)
    val shadowRadiusW = 28.dp.toPx() * jumpHeightRatio
    val shadowRadiusH = 8.dp.toPx() * jumpHeightRatio
    drawOval(
        color = Color(0x551A1A1A), // soft transparent dark shadow
        topLeft = Offset(playerX - shadowRadiusW, shadowY - shadowRadiusH),
        size = Size(shadowRadiusW * 2f, shadowRadiusH * 2f)
    )

    if (isCrashed) {
        // Render crashed fallen posture (kneeling / lying flat)
        // Head tilted down on ground
        drawCircle(
            color = Color(0xFFFFB6C1), // flesh skin tone
            radius = headRadius,
            center = Offset(playerX - headRadius, playerY + torsoHeight * 0.2f)
        )
        // Red jersey torso crumpled flat
        drawRoundRect(
            color = Color(0xFFFF3030),
            topLeft = Offset(playerX - 25.dp.toPx(), playerY + torsoHeight * 0.4f),
            size = Size(50.dp.toPx(), 18.dp.toPx()),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        )
        // Legs stretched horizontally
        drawLine(
            color = Color(0xFF1C1C1C), // dark pants
            start = Offset(playerX + 15.dp.toPx(), playerY + torsoHeight * 0.5f),
            end = Offset(playerX + 55.dp.toPx(), playerY + torsoHeight * 0.7f),
            strokeWidth = 10f,
            cap = StrokeCap.Round
        )
        return
    }

    // Dynamic tilt factor when sliding sideways
    val targetLaneX = when {
        laneOffset < -0.05f -> -1f
        laneOffset > 0.05f -> 1f
        else -> 0f
    }
    // Calculate tilt difference
    val slideSlideDiff = laneOffset - targetLaneX
    val tiltAngleDeg = slideSlideDiff * -15f // Tilt slightly inward to lean into lane change

    // AESTHETIC VISUAL TRAILS & FIELD EFFECTS
    // Underneath speed motion shadow duplicates
    val trailColor = when {
        shieldActive -> Color(0xFF00FFCC)
        magnetActive -> Color(0xFFFFCC00)
        else -> Color(0xFFFF007F)
    }
    
    // Draw 3 fading speed trail echoes behind the runner
    val motionOffsetFactor = if (isJumping) 1.3f else 1.0f
    for (step in 1..2) {
        val trailAlpha = 0.25f / step
        val trailY = playerY + (step * 25f * motionOffsetFactor)
        val scale = 1.0f - (step * 0.15f)
        
        // Face/Jersey echo
        drawRoundRect(
            color = trailColor.copy(alpha = trailAlpha),
            topLeft = Offset(playerX - 10.dp.toPx() * scale, trailY - headRadius + 4.dp.toPx()),
            size = Size(20.dp.toPx() * scale, torsoHeight * scale),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        )
        // Cap echo
        drawCircle(
            color = trailColor.copy(alpha = trailAlpha * 0.8f),
            radius = headRadius * 0.8f * scale,
            center = Offset(playerX, trailY - headRadius - 6.dp.toPx())
        )
    }

    // High fidelity concentric magnetic flux fields drawing
    if (magnetActive) {
        val pulse = sin(runCycle * 5f) * 8f
        // Dynamic magnetic field waves arching inwards
        for (wave in 0..1) {
            val waveAlpha = 0.6f - wave * 0.25f
            val extRadiusW = (100f + wave * 40f + pulse).dp.toPx()
            val extRadiusH = (80f + wave * 30f).dp.toPx()
            val waveTopY = playerY - extRadiusH * 0.5f
            
            drawArc(
                color = Color(0xFFFFCC00).copy(alpha = waveAlpha),
                startAngle = 135f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(playerX - extRadiusW * 0.5f, waveTopY),
                size = Size(extRadiusW, extRadiusH),
                style = Stroke(width = (3f - wave * 1f).dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }

    // Support drawing dynamic athletic running legs & arms
    // Run cycle swings back and forth based on runCycle speed
    val swingAmplitude = if (isJumping) 0f else 0.55f // Halt run swing at jump vaulting pose
    
    // Smooth skeletal angles
    val leftLegSwing = sin(runCycle) * swingAmplitude
    val rightLegSwing = -sin(runCycle) * swingAmplitude
    val leftArmSwing = -sin(runCycle) * swingAmplitude
    val rightArmSwing = sin(runCycle) * swingAmplitude

    // Coordinate joints
    val torsoTop = Offset(playerX, playerY - headRadius)
    val torsoBottom = Offset(playerX, playerY + torsoHeight * 0.6f)
    
    // A. Draw Torso / Athletic Shirt (Vivid orange/coral race singlet)
    drawRoundRect(
        color = Color(0xFFFF5722), // Vibrant athletic orange jersey
        topLeft = Offset(playerX - 10.dp.toPx(), playerY - headRadius + 4.dp.toPx()),
        size = Size(20.dp.toPx(), torsoHeight),
        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
    )
    // Draw race number bib "77" on the shirt chest
    drawRect(
        color = Color.White,
        topLeft = Offset(playerX - 5.dp.toPx(), playerY),
        size = Size(10.dp.toPx(), 8.dp.toPx())
    )
    drawLine(
        color = Color.Black,
        start = Offset(playerX - 3.dp.toPx(), playerY + 3.dp.toPx()),
        end = Offset(playerX + 3.dp.toPx(), playerY + 3.dp.toPx()),
        strokeWidth = 1.5f
    )

    // B. Draw Head & Hair (Athlete wearing cyan forward cap)
    drawCircle(
        color = Color(0xFFFCD5B5), // Skin tone peach
        radius = headRadius * 0.85f,
        center = Offset(playerX, playerY - headRadius - 6.dp.toPx())
    )
    // Sporty Backwardscap (Vivid Cyan)
    val capY = playerY - headRadius - 10.dp.toPx()
    drawArc(
        color = Color(0xFF00E5FF),
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(playerX - headRadius * 0.9f, capY - 4.dp.toPx()),
        size = Size(headRadius * 1.8f, headRadius * 1.8f)
    )
    // Cap visor beak
    drawRoundRect(
        color = Color(0xFF00E5FF),
        topLeft = Offset(playerX - headRadius * 1.4f, capY + 1.dp.toPx()),
        size = Size(12.dp.toPx(), 3.dp.toPx()),
        cornerRadius = CornerRadius(2f, 2f)
    )

    // C. Draw Legs (Hips down)
    val leftHip = Offset(playerX - hipWidth * 0.4f, torsoBottom.y - 2.dp.toPx())
    val rightHip = Offset(playerX + hipWidth * 0.4f, torsoBottom.y - 2.dp.toPx())

    if (isJumping) {
        // Dedicated Hurdle Aerial Vault jumping pose (legs tucked high in flight)
        // Left Knee raised forward
        val leftKnee = Offset(playerX - 18.dp.toPx(), leftHip.y + 12.dp.toPx())
        val leftAnkle = Offset(playerX - 10.dp.toPx(), leftKnee.y + 16.dp.toPx())
        
        drawLine(
            color = Color(0xFF222222), // Running tights pants
            start = leftHip,
            end = leftKnee,
            strokeWidth = 9f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFFFFB6C1), // Leg skin
            start = leftKnee,
            end = leftAnkle,
            strokeWidth = 7f,
            cap = StrokeCap.Round
        )
        // Left Yellow Shoe
        drawCircle(Color(0xFFFFFF00), radius = 5.5f, center = leftAnkle)

        // Right Leg extended backward for balance
        val rightKnee = Offset(playerX + 16.dp.toPx(), rightHip.y + 5.dp.toPx())
        val rightAnkle = Offset(playerX + 22.dp.toPx(), rightKnee.y + 14.dp.toPx())
        drawLine(
            color = Color(0xFF222222),
            start = rightHip,
            end = rightKnee,
            strokeWidth = 9f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFFFFB6C1),
            start = rightKnee,
            end = rightAnkle,
            strokeWidth = 7f,
            cap = StrokeCap.Round
        )
        // Right Yellow Shoe
        drawCircle(Color(0xFFFFFF00), radius = 5.5f, center = rightAnkle)

        // Arms thrown high in air for flight stance
        drawLine(
            color = Color(0xFFFCD5B5),
            start = Offset(playerX - 10.dp.toPx(), torsoTop.y + 10.dp.toPx()),
            end = Offset(playerX - 18.dp.toPx(), torsoTop.y - 20.dp.toPx()),
            strokeWidth = 7f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFFFCD5B5),
            start = Offset(playerX + 10.dp.toPx(), torsoTop.y + 10.dp.toPx()),
            end = Offset(playerX + 18.dp.toPx(), torsoTop.y - 20.dp.toPx()),
            strokeWidth = 7f,
            cap = StrokeCap.Round
        )

    } else {
        // Normal ground running gait dynamics
        // Left Leg
        val lKneeY = leftHip.y + 18.dp.toPx()
        val lKneeX = leftHip.x + leftLegSwing * 18.dp.toPx()
        val leftKnee = Offset(lKneeX, lKneeY)
        val lAnkleX = leftKnee.x + sin(runCycle + 0.3f) * 8.dp.toPx()
        val lAnkleY = leftKnee.y + 16.dp.toPx() + (if (leftLegSwing < 0) -4.dp.toPx() else 2.dp.toPx())
        val leftAnkle = Offset(lAnkleX, lAnkleY)

        drawLine(
            color = Color(0xFF111111), // Running tights shorts
            start = leftHip,
            end = leftKnee,
            strokeWidth = 8.5f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFFFCD5B5), // Skin
            start = leftKnee,
            end = leftAnkle,
            strokeWidth = 6.5f,
            cap = StrokeCap.Round
        )
        // Yellow Running sneakers
        drawCircle(color = Color(0xFFE2F017), radius = 5.5f, center = leftAnkle)

        // Right Leg
        val rKneeY = rightHip.y + 18.dp.toPx()
        val rKneeX = rightHip.x + rightLegSwing * 18.dp.toPx()
        val rightKnee = Offset(rKneeX, rKneeY)
        val rAnkleX = rightKnee.x + sin(runCycle + Math.PI.toFloat() + 0.3f) * 8.dp.toPx()
        val rAnkleY = rightKnee.y + 16.dp.toPx() + (if (rightLegSwing < 0) -4.dp.toPx() else 2.dp.toPx())
        val rightAnkle = Offset(rAnkleX, rAnkleY)

        drawLine(
            color = Color(0xFF111111),
            start = rightHip,
            end = rightKnee,
            strokeWidth = 8.5f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFFFCD5B5),
            start = rightKnee,
            end = rightAnkle,
            strokeWidth = 6.5f,
            cap = StrokeCap.Round
        )
        drawCircle(color = Color(0xFFE2F017), radius = 5.5f, center = rightAnkle)

        // D. Draw Arms swinging
        // Left Arm
        val leftShoulder = Offset(playerX - 10.dp.toPx(), torsoTop.y + 8.dp.toPx())
        val leftElbow = Offset(leftShoulder.x + leftArmSwing * 12.dp.toPx() - 4.dp.toPx(), leftShoulder.y + 14.dp.toPx())
        drawLine(
            color = Color(0xFFFCD5B5),
            start = leftShoulder,
            end = leftElbow,
            strokeWidth = 6.5f,
            cap = StrokeCap.Round
        )
        drawCircle(Color(0xFFFCD5B5), radius = 3.5f, center = leftElbow)

        // Right Arm
        val rightShoulder = Offset(playerX + 10.dp.toPx(), torsoTop.y + 8.dp.toPx())
        val rightElbow = Offset(rightShoulder.x + rightArmSwing * 12.dp.toPx() + 4.dp.toPx(), rightShoulder.y + 14.dp.toPx())
        drawLine(
            color = Color(0xFFFCD5B5),
            start = rightShoulder,
            end = rightElbow,
            strokeWidth = 6.5f,
            cap = StrokeCap.Round
        )
        drawCircle(Color(0xFFFCD5B5), radius = 3.5f, center = rightElbow)
    }

    // Draw Active Protection Shield surrounding the runner
    if (shieldActive) {
        val shieldOrbRadius = 45.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x0000FFFF), Color(0x7300E5FF)),
                center = Offset(playerX, playerY + 8.dp.toPx()),
                radius = shieldOrbRadius
            ),
            radius = shieldOrbRadius,
            center = Offset(playerX, playerY + 8.dp.toPx())
        )
        // High visibility bright neon blue thin crust
        drawCircle(
            color = Color(0xFF00E5FF).copy(alpha = 0.5f + 0.3f * sin(runCycle * 4f)),
            radius = shieldOrbRadius,
            center = Offset(playerX, playerY + 8.dp.toPx()),
            style = Stroke(width = 2.5f)
        )
    }
}

// ==========================================
// GAME Overlays AND INTERACTIVE CONTROLS
// ==========================================

@Composable
fun PlayHUD(
    score: Float,
    coinsCollected: Int,
    shieldDurationLeft: Float,
    magnetDurationLeft: Float,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onTriggerJump: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("in_game_hud")
    ) {
        // Status Boards bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 50.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Score Board (Furturistic Cyan Digital display)
            Row(
                modifier = Modifier
                    .shadow(6.dp, RoundedCornerShape(12.dp))
                    .border(1.5.dp, Color(0xFF00FFCC).copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                    .background(Color(0xE60A0616), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SCORE: ",
                    color = Color(0xFF00FFCC),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = score.toInt().toString(),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
            }

            // Coin Board (Vibrant Hot Yellow)
            Row(
                modifier = Modifier
                    .shadow(6.dp, RoundedCornerShape(12.dp))
                    .border(1.5.dp, Color(0xFFFFD700).copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                    .background(Color(0xE6191201), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Coin Icon",
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = coinsCollected.toString(),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // Active Power-up Progress displays
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 20.dp, vertical = 110.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (shieldDurationLeft > 0f) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .background(Color(0xCC050E14), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "🛡️ SHIELD  ",
                        color = Color(0xFF00E5FF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    LinearProgressIndicator(
                        progress = { shieldDurationLeft / 400f },
                        modifier = Modifier
                            .width(80.dp)
                            .height(6.dp)
                            .clip(CircleShape),
                        color = Color(0xFF00E5FF),
                        trackColor = Color(0x33FFFFFF)
                    )
                }
            }
            if (magnetDurationLeft > 0f) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .border(1.dp, Color(0xFFFFCC00).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .background(Color(0xCC171002), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "🧲 MAGNET  ",
                        color = Color(0xFFFFCC00),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    LinearProgressIndicator(
                        progress = { magnetDurationLeft / 500f },
                        modifier = Modifier
                            .width(80.dp)
                            .height(6.dp)
                            .clip(CircleShape),
                        color = Color(0xFFFFCC00),
                        trackColor = Color(0x33FFFFFF)
                    )
                }
            }
        }

        // On-screen manual Arrow controls (Enables effortless streaming emulator/Keyboard testing playability)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 35.dp)
                .fillMaxWidth(0.95f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // MOVE LEFT
            Button(
                onClick = onMoveLeft,
                modifier = Modifier
                    .size(65.dp)
                    .border(2.dp, Color(0xFFFF007F).copy(alpha = 0.8f), CircleShape)
                    .testTag("left_lane_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xBF0F0510)),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = "◀",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black
                )
            }

            // VAULT JUMP
            Button(
                onClick = onTriggerJump,
                modifier = Modifier
                    .size(85.dp)
                    .shadow(8.dp, CircleShape)
                    .border(2.5.dp, Color.White, CircleShape)
                    .testTag("jump_action_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)), // Electric coral-orange
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "▲",
                        color = Color.White,
                        fontSize = 28.sp,
                        lineHeight = 28.sp
                    )
                    Text(
                        text = "JUMP",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
            }

            // MOVE RIGHT
            Button(
                onClick = onMoveRight,
                modifier = Modifier
                    .size(65.dp)
                    .border(2.dp, Color(0xFF00FFCC).copy(alpha = 0.8f), CircleShape)
                    .testTag("right_lane_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xBF051210)),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = "▶",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
fun StartScreenOverlay(
    highScore: Float,
    onStartClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF04020B), // Deepest violet-black space
                        Color(0xFF100324), // Neo-sunset deep plum
                        Color(0xFF1A0021)  // Bottom cosmic abyss
                    )
                )
            )
            .testTag("start_screen_overlay")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(30.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Visual athlete crown golden star
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = "Runner Trophy Icon",
                tint = Color(0xFFFFD700),
                modifier = Modifier
                    .size(80.dp)
                    .padding(bottom = 12.dp)
            )

            // Epic Display game title
            Text(
                text = "LANE RUNNER",
                color = Color(0xFF00FFCC), // Energetic neon cyan glow
                fontSize = 46.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp
            )
            
            Text(
                text = "CYBERNETIC SPEEDRUN",
                color = Color(0xFFFF007F), // Vivid laser pink
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(bottom = 26.dp)
            )

            // High Score Boarding with Neon border
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x1A00FFCC)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .border(1.5.dp, Color(0xFF00FFCC).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .padding(bottom = 30.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🏆 PERSONAL BEST",
                        color = Color(0xFFFFD700),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${highScore.toInt()} PTS",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            // How To Play Manual Cards (Sleek dark panel)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xD90A0618)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .border(1.dp, Color(0xFF4B2375).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(bottom = 36.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "🕹️ SYSTEM INTERFACES:",
                        color = Color(0xFFFFD700),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "• Switch Lanes: Swipe LEFT/RIGHT (or Tap virtual ◀/▶ buttons)",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.5.sp
                    )
                    Text(
                        text = "• Hurdle Vault: Swipe UP (or Tap central JUMP button)",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.5.sp
                    )
                    Text(
                        text = "• Warning: Wall blocks are solid concrete—DODGE ONLY!",
                        color = Color(0xFFFF4560),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Big Action Bouncing Play Button
            Button(
                onClick = onStartClick,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(60.dp)
                    .shadow(10.dp, RoundedCornerShape(30.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(30.dp))
                    .testTag("start_game_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC)), // Cyber green-cyan
                shape = RoundedCornerShape(30.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Icon",
                        tint = Color(0xFF021B16),
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "INITIALIZE RUN",
                        color = Color(0xFF021B16),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
fun GameOverScreenOverlay(
    score: Int,
    coins: Int,
    highScore: Int,
    onRestartClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFC1B020B), // Crimson dark obsidian shade
                        Color(0xFA0B0002)
                    )
                )
            )
            .testTag("game_over_overlay")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(30.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Spark warning crashed icon
            Text(
                text = "💥",
                fontSize = 62.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Text(
                text = "CRITICAL SPLAT",
                color = Color(0xFFFF8C00),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            
            Text(
                text = "GAME OVER",
                color = Color(0xFFFF2E63), // Intense warning hot-red
                fontSize = 46.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 28.dp)
            )

            // Current Stats Card with crimson border
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xCC1A050A)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .border(1.5.dp, Color(0xFFFF2E63).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .padding(bottom = 45.dp)
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("FINAL SPEED SCORE", color = Color(0xFFFFA2A2), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("$score", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                    HorizontalDivider(color = Color(0x1AFFFFFF))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("SECURED COINS", color = Color(0xFFFFA2A2), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("★ $coins", color = Color(0xFFFFD700), fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                    HorizontalDivider(color = Color(0x1AFFFFFF))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ALL-TIME RECORD", color = Color(0xFFFFA2A2), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("$highScore", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            // Play Restart Button
            Button(
                onClick = onRestartClick,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(60.dp)
                    .shadow(10.dp, RoundedCornerShape(30.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(30.dp))
                    .testTag("reset_game_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700)),
                shape = RoundedCornerShape(30.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Restart Icon",
                        tint = Color.Black,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "RE-INITIALIZE RUN",
                        color = Color.Black,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}
