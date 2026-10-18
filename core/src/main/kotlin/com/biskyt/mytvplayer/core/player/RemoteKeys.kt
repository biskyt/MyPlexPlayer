package com.biskyt.mytvplayer.core.player

/** Remote control buttons the player reacts to (mapped from Android key codes by the app). */
enum class RemoteKey { LEFT, RIGHT, UP, DOWN, OK, BACK, PLAY_PAUSE, PLAY, PAUSE, STOP, REWIND, FAST_FORWARD, GUIDE, MENU, CHANNEL_UP, CHANNEL_DOWN, DIGIT }

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
 * left/right always skip (by the user's settings), up/down zap channels on live TV when nothing is open,
 * Back closes the topmost overlay or stops playback.
 */
class KeyActionMapper(private val skipBackMs: Long, private val skipForwardMs: Long) {

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
            RemoteKey.UP -> if (isLive && overlay == OverlayState.NONE) PlayerAction.Zap(-1) else PlayerAction.None
            RemoteKey.DOWN -> if (isLive && overlay == OverlayState.NONE) PlayerAction.Zap(1) else PlayerAction.None
            RemoteKey.CHANNEL_UP -> if (isLive) PlayerAction.Zap(-1) else PlayerAction.None
            RemoteKey.CHANNEL_DOWN -> if (isLive) PlayerAction.Zap(1) else PlayerAction.None
            RemoteKey.OK -> if (overlay == OverlayState.NONE) PlayerAction.ToggleOverlay else PlayerAction.None
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
