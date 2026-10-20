package com.biskyt.mytvplayer.core.player

/** Remote control buttons the player reacts to (mapped from Android key codes by the app). */
enum class RemoteKey {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    OK,
    BACK,
    PLAY_PAUSE,
    PLAY,
    PAUSE,
    STOP,
    REWIND,
    FAST_FORWARD,
    GUIDE,
    MENU,
    CHANNEL_UP,
    CHANNEL_DOWN,
    DIGIT,
}

sealed interface PlayerAction {
    data class SeekBy(val ms: Long) : PlayerAction
    data class Zap(val step: Int) : PlayerAction
    data class Digit(val value: Int) : PlayerAction
    data object ToggleOverlay : PlayerAction
    data object HideOverlay : PlayerAction
    data object TogglePlayPause : PlayerAction
    data object Play : PlayerAction
    data object Pause : PlayerAction
    data object StopAndExit : PlayerAction
    data object OpenGuide : PlayerAction
    data object CloseGuide : PlayerAction
    data object OpenMenu : PlayerAction
    data object None : PlayerAction
}

/** What is currently shown on top of the video; decides how keys are interpreted. */
enum class OverlayState { NONE, CONTROLS, GUIDE }

/**
 * Pure mapping from a remote key to a player action, so the behaviour is easy to test:
 * left/right always skip (by the user's settings); the channel buttons zap on live TV, and up/down too when
 * [upDownZaps] (else, with nothing open, they show the controls); Back closes the topmost overlay or stops playback.
 */
class KeyActionMapper(private val skipBackMs: Long, private val skipForwardMs: Long, private val upDownZaps: Boolean = false) {

    fun map(key: RemoteKey, overlay: OverlayState, isLive: Boolean, digit: Int = 0): PlayerAction {
        if (overlay == OverlayState.GUIDE) {
            // The guide panel handles its own navigation; only Back/Guide close it.
            return when (key) {
                RemoteKey.BACK, RemoteKey.GUIDE -> PlayerAction.CloseGuide
                RemoteKey.STOP -> PlayerAction.StopAndExit
                else -> PlayerAction.None
            }
        }
        return when (key) {
            RemoteKey.LEFT, RemoteKey.REWIND -> PlayerAction.SeekBy(-skipBackMs)
            RemoteKey.RIGHT, RemoteKey.FAST_FORWARD -> PlayerAction.SeekBy(skipForwardMs)
            RemoteKey.UP, RemoteKey.DOWN -> when {
                overlay != OverlayState.NONE -> PlayerAction.None
                isLive && upDownZaps -> PlayerAction.Zap(if (key == RemoteKey.UP) -1 else 1)
                else -> PlayerAction.ToggleOverlay
            }
            RemoteKey.CHANNEL_UP -> if (isLive) PlayerAction.Zap(-1) else PlayerAction.None
            RemoteKey.CHANNEL_DOWN -> if (isLive) PlayerAction.Zap(1) else PlayerAction.None
            RemoteKey.OK -> PlayerAction.ToggleOverlay
            RemoteKey.BACK -> if (overlay == OverlayState.CONTROLS) PlayerAction.HideOverlay else PlayerAction.StopAndExit
            RemoteKey.STOP -> PlayerAction.StopAndExit
            RemoteKey.PLAY_PAUSE -> PlayerAction.TogglePlayPause
            RemoteKey.PLAY -> PlayerAction.Play
            RemoteKey.PAUSE -> PlayerAction.Pause
            RemoteKey.GUIDE -> if (isLive) PlayerAction.OpenGuide else PlayerAction.None
            RemoteKey.MENU -> PlayerAction.OpenMenu
            RemoteKey.DIGIT -> if (isLive) PlayerAction.Digit(digit) else PlayerAction.None
        }
    }
}

/**
 * Where scrubbing goes next: [stepMs] on from [fromMs], kept within [range]. Holding the button ([repeat] is how many
 * times it has repeated) speeds up, so the length of a film can be crossed in a few seconds.
 */
fun scrubTarget(fromMs: Long, stepMs: Long, repeat: Int, range: LongRange): Long {
    val speed = (1 + repeat / SCRUB_SPEED_UP_EVERY).coerceAtMost(SCRUB_MAX_SPEED)
    return (fromMs + stepMs * speed).coerceIn(range)
}

private const val SCRUB_SPEED_UP_EVERY = 5
private const val SCRUB_MAX_SPEED = 12
