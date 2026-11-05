package com.suisheng.player.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Routes steering-wheel / Bluetooth / voice media keys to custom playlist logic.
 */
class CarPlaylistPlayer(
    player: Player,
    private val onSkipNext: () -> Unit,
    private val onSkipPrevious: () -> Unit,
    private val hasPlaylist: () -> Boolean,
) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands {
        return super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        return when (command) {
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS -> hasPlaylist()
            else -> super.isCommandAvailable(command)
        }
    }

    override fun seekToNextMediaItem() {
        onSkipNext()
    }

    override fun seekToPreviousMediaItem() {
        onSkipPrevious()
    }

    override fun seekToNext() {
        onSkipNext()
    }

    override fun seekToPrevious() {
        onSkipPrevious()
    }
}
