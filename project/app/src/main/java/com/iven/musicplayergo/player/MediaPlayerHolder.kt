package com.iven.musicplayergo.player

import android.annotation.TargetApi
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.MediaMetadataCompat.*
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.*
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toBitmap
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.iven.musicplayergo.GoConstants
import com.iven.musicplayergo.R
import com.iven.musicplayergo.extensions.*
import com.iven.musicplayergo.goPreferences
import com.iven.musicplayergo.helpers.ListsHelper
import com.iven.musicplayergo.helpers.VersioningHelper
import com.iven.musicplayergo.models.Music
import com.iven.musicplayergo.models.SavedEqualizerSettings
import com.iven.musicplayergo.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.ln


/**
 * Exposes the functionality of the [MediaPlayer]
 */

// The volume we set the media player to when we lose audio focus, but are
// allowed to reduce the volume instead of stopping playback.
private const val VOLUME_DUCK = 0.2f

// The volume we set the media player when we have audio focus.
private const val VOLUME_NORMAL = 1.0f

// We don't have audio focus, and can't duck (play at a low volume)
private const val AUDIO_NO_FOCUS_NO_DUCK = 0

// We don't have focus, but can duck (play at a low volume)
private const val AUDIO_NO_FOCUS_CAN_DUCK = 1

// We have full audio focus
private const val AUDIO_FOCUSED = 2

// The headset connection states (0,1)
private const val HEADSET_DISCONNECTED = 0
private const val HEADSET_CONNECTED = 1

class MediaPlayerHolder(private val playerService: PlayerService) :
    MediaPlayer.OnErrorListener,
    MediaPlayer.OnCompletionListener,
    MediaPlayer.OnPreparedListener {

    private val mMediaSessionActions = ACTION_PLAY or ACTION_PAUSE or ACTION_PLAY_PAUSE or ACTION_SKIP_TO_NEXT or ACTION_SKIP_TO_PREVIOUS or ACTION_STOP or ACTION_SEEK_TO

    lateinit var mediaPlayerInterface: MediaPlayerInterface

    // Equalizer
    private var mEqualizer: Equalizer? = null
    private var mBassBoost: BassBoost? = null
    private var mVirtualizer: Virtualizer? = null
    private var sHasOpenedAudioEffects = false

    // Audio focus
    private var mAudioManager = playerService.getSystemService<AudioManager>()!!
    private lateinit var mAudioFocusRequestCompat: AudioFocusRequestCompat

    private val sFocusEnabled get() = goPreferences.isFocusEnabled
    private var mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK
    private var sPlayOnFocusGain = false

    private val mOnAudioFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> mCurrentAudioFocusState = AUDIO_FOCUSED
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    // Audio focus was lost, but it's possible to duck (i.e.: play quietly)
                    mCurrentAudioFocusState = AUDIO_NO_FOCUS_CAN_DUCK
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // Lost audio focus, but will gain it back (shortly), so note whether
                    // playback should resume
                    mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK
                    sPlayOnFocusGain =
                        isMediaPlayer && state == GoConstants.PLAYING || state == GoConstants.RESUMED
                }
                AudioManager.AUDIOFOCUS_LOSS ->
                    // Lost audio focus, probably "permanently"
                    mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK
            }
            // Update the player state based on the change
            if (isPlaying) {
                configurePlayerState()
            }
        }

    // Media player
    private lateinit var mediaPlayer: MediaPlayer
    private var mExecutor: ScheduledExecutorService? = null
    private var mSeekBarPositionUpdateTask: Runnable? = null

    var currentSong: Music? = null
    private var mPlayingSongs: List<Music>? = null
    var launchedBy = GoConstants.ARTIST_VIEW

    var currentVolumeInPercent = goPreferences.latestVolume
    private var currentPlaybackSpeed = goPreferences.latestPlaybackSpeed

    val playerPosition
        get() = if (!isMediaPlayer) {
            goPreferences.latestPlayedSong?.startFrom!!
        } else {
            mediaPlayer.currentPosition
        }

    // Media player state/booleans
    val isPlaying get() = isMediaPlayer && mediaPlayer.isPlaying
    val isMediaPlayer get() = ::mediaPlayer.isInitialized

    private var sNotificationForeground = false

    val isCurrentSong get() = currentSong != null
    private val sPlaybackSpeedPersisted get() = goPreferences.isPlaybackSpeedPersisted
    var isRepeat1X = false
    var isLooping = false
    var isPauseOnEnd = false

    // isQueue saves the current song when queue starts
    var isQueue: Music? = null
    var isQueueStarted = false
    var queueSongs = mutableListOf<Music>()
    var canRestoreQueue = false
    var restoreQueueSong: Music? = null

    var isSongFromPrefs = false

    var state = GoConstants.PAUSED
    var isPlay = false

    // Notifications
    private lateinit var mPlayerBroadcastReceiver: PlayerBroadcastReceiver
    private val mMusicNotificationManager: MusicNotificationManager get() = playerService.musicNotificationManager

    private fun startForeground() {
        if (!sNotificationForeground) {
            mMusicNotificationManager.createNotification {
                playerService.startForeground(GoConstants.NOTIFICATION_ID, it)
                sNotificationForeground = true
            }

        } else {
            with(mMusicNotificationManager) {
                updatePlayPauseAction()
                updateRepeatIcon()
                updateNotificationContent {
                    updateNotification()
                }
            }
        }
    }

    fun registerActionsReceiver() {
        mPlayerBroadcastReceiver = PlayerBroadcastReceiver()
        val intentFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_MEDIA_BUTTON)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        playerService.registerReceiver(mPlayerBroadcastReceiver, intentFilter)
    }

    private fun unregisterActionsReceiver() {
        try {
            playerService.unregisterReceiver(mPlayerBroadcastReceiver)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        }
    }

    private fun createCustomEqualizer() {
        if (mEqualizer == null) {
            try {
                mBassBoost = BassBoost(0, mediaPlayer.audioSessionId)
                mVirtualizer = Virtualizer(0, mediaPlayer.audioSessionId)
                mEqualizer = Equalizer(0, mediaPlayer.audioSessionId)
                restoreCustomEqSettings()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getEqualizer() = Triple(mEqualizer, mBassBoost, mVirtualizer)

    fun setEqualizerEnabled(isEnabled: Boolean) {
        mEqualizer?.enabled = isEnabled
        mBassBoost?.enabled = isEnabled
        mVirtualizer?.enabled = isEnabled
    }

    fun onSaveEqualizerSettings(selectedPreset: Int, bassBoost: Short, virtualizer: Short) {
        mEqualizer?.let { equalizer ->
            goPreferences.savedEqualizerSettings = SavedEqualizerSettings(
                equalizer.enabled,
                selectedPreset,
                equalizer.properties.bandLevels.toList(),
                bassBoost,
                virtualizer
            )
        }
    }

    fun updateCurrentSong(song: Music?, albumSongs: List<Music>?, songLaunchedBy: String) {
        currentSong = song
        mPlayingSongs = albumSongs
        launchedBy = songLaunchedBy
    }

    fun updateCurrentSongs(sortedMusic: List<Music>?) {
        mPlayingSongs = if (sortedMusic != null) {
            sortedMusic
        } else {
            val sorting = if (goPreferences.songsVisualization == GoConstants.FN) {
                GoConstants.ASCENDING_SORTING
            } else {
                GoConstants.TRACK_SORTING
            }
            ListsHelper.getSortedMusicList(sorting, mPlayingSongs?.toMutableList())
        }
    }

    fun getCurrentAlbumSize() = mPlayingSongs?.size ?: 0

    fun updateMediaSessionMetaData() {
        with(MediaMetadataCompat.Builder()) {
            currentSong?.run {
                putLong(METADATA_KEY_DURATION, duration)
                putString(METADATA_KEY_ARTIST, artist)
                putString(METADATA_KEY_AUTHOR, artist)
                putString(METADATA_KEY_COMPOSER, artist)
                putString(METADATA_KEY_TITLE, title)
                putString(METADATA_KEY_DISPLAY_TITLE, title)
                putString(METADATA_KEY_ALBUM_ARTIST, album)
                putString(METADATA_KEY_DISPLAY_SUBTITLE, album)
                putString(METADATA_KEY_ALBUM, album)
                putBitmap(
                    METADATA_KEY_DISPLAY_ICON,
                    ContextCompat.getDrawable(playerService, R.drawable.ic_music_note)?.toBitmap()
                )
                mPlayingSongs?.let { songs ->
                    putLong(METADATA_KEY_NUM_TRACKS, songs.size.toLong())
                    putLong(METADATA_KEY_TRACK_NUMBER, songs.indexOf(this).toLong())
                }

                if (goPreferences.isCovers) {
                    albumId?.waitForCover(playerService, canLoadDefault = false) { bmp ->
                        putBitmap(METADATA_KEY_ALBUM_ART, bmp)
                    }
                }
            }
            playerService.getMediaSession().setMetadata(build())
        }
    }

    override fun onCompletion(mediaPlayer: MediaPlayer) {

        when {
            isPauseOnEnd -> {
                pauseMediaPlayer()
                repeat(updatePlaybackStatus = true)
            }
            isRepeat1X or isLooping -> if (isMediaPlayer) {
                repeatSong(0)
            }
            isQueue != null && !canRestoreQueue -> manageQueue(isNext = true)
            canRestoreQueue -> manageRestoredQueue()
            else -> {
                if (mPlayingSongs?.indexOf(currentSong) == mPlayingSongs?.size?.minus(
                        1
                    )
                ) {
                    if (goPreferences.onListEnded == GoConstants.CONTINUE) {
                        skip(isNext = true)
                    } else {
                        synchronized(pauseMediaPlayer()) {
                            mMusicNotificationManager.cancelNotification()
                            R.string.error_list_ended.toToast(playerService)
                        }
                    }
                } else {
                    skip(isNext = true)
                }
            }
        }

        playerService.acquireWakeLock()
        mediaPlayerInterface.onStateChanged()
        mediaPlayerInterface.onPlaybackCompleted()
    }

    fun onRestartSeekBarCallback() {
        if (mExecutor == null) {
            startUpdatingCallbackWithPosition()
        }
    }

    fun onPauseSeekBarCallback() {
        stopUpdatingCallbackWithPosition()
    }

    fun onUpdateDefaultAlbumArt(bitmapRes: Bitmap?) {
        mMusicNotificationManager.onUpdateDefaultAlbumArt(bitmapRes, updateNotification = isPlaying)
    }

    fun onHandleNotificationUpdate(isAdditionalActionsChanged: Boolean) {
        mMusicNotificationManager.onHandleNotificationUpdate(isAdditionalActionsChanged = isAdditionalActionsChanged)
    }

    fun tryToGetAudioFocus() {
        if (!::mAudioFocusRequestCompat.isInitialized) {
            initializeAudioFocusRequestCompat()
        }
        val requestFocus =
            AudioManagerCompat.requestAudioFocus(mAudioManager, mAudioFocusRequestCompat)
        mCurrentAudioFocusState = when (requestFocus) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> AUDIO_FOCUSED
            else -> AUDIO_NO_FOCUS_NO_DUCK
        }
    }

    private fun initializeAudioFocusRequestCompat() {
        val audioAttributes = AudioAttributesCompat.Builder()
            .setUsage(AudioAttributesCompat.USAGE_MEDIA)
            .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
            .build()
        mAudioFocusRequestCompat =
            AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(mOnAudioFocusChangeListener)
                .build()
    }

    fun giveUpAudioFocus() {
        if (::mAudioFocusRequestCompat.isInitialized) {
            AudioManagerCompat.abandonAudioFocusRequest(mAudioManager, mAudioFocusRequestCompat)
            mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK
        }
    }

    private fun updatePlaybackStatus(updateUI: Boolean) {
        playerService.getMediaSession().setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(mMediaSessionActions)
                .setState(
                    if (mediaPlayer.isPlaying) {
                        GoConstants.PLAYING
                    } else {
                        GoConstants.PAUSED
                           },
                    mediaPlayer.currentPosition.toLong(),
                    currentPlaybackSpeed
                ).build()
        )
        if (updateUI) {
            mediaPlayerInterface.onStateChanged()
        }
    }

    private fun startOrChangePlaybackSpeed() {
        if (sPlaybackSpeedPersisted && VersioningHelper.isMarshmallow()) {
            mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(currentPlaybackSpeed)
        } else {
            mediaPlayer.start()
        }
    }

    fun resumeMediaPlayer() {
        if (!isPlaying) {
            if (sFocusEnabled) {
                tryToGetAudioFocus()
            }
            startOrChangePlaybackSpeed()

            state = if (isSongFromPrefs) {
                isSongFromPrefs = false
                GoConstants.PLAYING
            } else {
                GoConstants.RESUMED
            }

            updatePlaybackStatus(updateUI = true)

            startForeground()

            isPlay = true
        }
    }

    fun pauseMediaPlayer() {
        mediaPlayer.pause()
        playerService.stopForeground(false)
        sNotificationForeground = false
        state = GoConstants.PAUSED
        updatePlaybackStatus(updateUI = true)
        with(mMusicNotificationManager) {
            updatePlayPauseAction()
            updateNotification()
        }
        mediaPlayerInterface.onBackupSong()
    }

    fun repeatSong(startFrom: Int) {
        isRepeat1X = false
        mediaPlayer.setOnSeekCompleteListener { mp ->
            mp.setOnSeekCompleteListener(null)
            play()
        }
        mediaPlayer.seekTo(startFrom)
    }

    private fun manageQueue(isNext: Boolean) {

        if (isSongFromPrefs) {
            isSongFromPrefs = false
        }

        when {
            isQueueStarted -> currentSong = getSkipSong(isNext = isNext)
            else -> {
                isQueueStarted = true
                currentSong = queueSongs.first()
            }
        }
        initMediaPlayer(currentSong)
    }

    private fun manageRestoredQueue() {
        currentSong = restoreQueueSong
        initMediaPlayer(currentSong)

        isQueueStarted = true
        restoreQueueSong = null
        canRestoreQueue = false
    }

    private fun getSkipSong(isNext: Boolean): Music? {

        val listToSeek = if (isQueue != null) {
            queueSongs
        } else {
            mPlayingSongs
        }

        val songToSkip = if (isQueue != null && queueSongs.isNotEmpty() && isQueueStarted) {
            currentSong
        } else {
            currentSong?.toSavedMusic(0, GoConstants.ARTIST_VIEW)
        }

        try {
            return listToSeek?.get(
                if (isNext) {
                    listToSeek.indexOf(songToSkip).plus(1)
                } else {
                    listToSeek.indexOf(songToSkip).minus(1)
                }
            )
        } catch (e: IndexOutOfBoundsException) {
            e.printStackTrace()
            return when {
                isQueue != null -> {
                    val returnedSong = isQueue
                    if (isNext) {
                        setQueueEnabled(enabled = false, canSkip = false)
                    } else {
                        isQueueStarted = false
                    }
                    returnedSong
                }
                else -> if (listToSeek?.indexOf(songToSkip) != 0) {
                    listToSeek?.first()
                } else {
                    listToSeek.last()
                }
            }
        }
    }

    /**
     * Syncs the mMediaPlayer position with mSeekBarPositionUpdateTask via recurring task.
     */
    private fun startUpdatingCallbackWithPosition() {

        if (mSeekBarPositionUpdateTask == null) {
            mSeekBarPositionUpdateTask =
                Runnable { updateProgressCallbackTask() }
        }

        mExecutor = Executors.newSingleThreadScheduledExecutor()
        mExecutor?.scheduleAtFixedRate(
            mSeekBarPositionUpdateTask!!,
            0,
            1000,
            TimeUnit.MILLISECONDS
        )
    }

    // Reports media playback position to mPlaybackProgressCallback.
    private fun stopUpdatingCallbackWithPosition() {
        mExecutor?.shutdownNow()
        mExecutor = null
        mSeekBarPositionUpdateTask = null
    }

    private fun updateProgressCallbackTask() {
        if (isPlaying) {
            val currentPosition = mediaPlayer.currentPosition
            mediaPlayerInterface.onPositionChanged(currentPosition)
        }
    }

    fun instantReset() {
        if (isMediaPlayer && !isSongFromPrefs) {
            when {
                mediaPlayer.currentPosition < 5000 -> skip(isNext = false)
                else -> repeatSong(0)
            }
        } else {
            skip(isNext = false)
        }
    }

    /**
     * Once the [MediaPlayer] is released, it can't be used again, and another one has to be
     * created. In the onStop() method of the [MainActivity] the [MediaPlayer] is
     * released. Then in the onStart() of the [MainActivity] a new [MediaPlayer]
     * object has to be created. That's why this method is private, and called by load(int) and
     * not the constructor.
     */
    fun initMediaPlayer(song: Music?) {

        try {
            if (isMediaPlayer) {
                mediaPlayer.reset()
            } else {
                mediaPlayer = MediaPlayer().apply {

                    setOnPreparedListener(this@MediaPlayerHolder)
                    setOnCompletionListener(this@MediaPlayerHolder)
                    setOnErrorListener(this@MediaPlayerHolder)
                    setWakeMode(playerService, PowerManager.PARTIAL_WAKE_LOCK)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                }
            }

            song?.id?.toContentUri()?.let { uri ->
                mediaPlayer.setDataSource(playerService, uri)
            }

            mediaPlayer.prepare()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        val errorMessage = "MediaPlayer error: $what"
        errorMessage.toToast(playerService)
        Log.d(errorMessage, errorMessage)
        mediaPlayer.reset()
        return true
    }

    override fun onPrepared(mp: MediaPlayer) {

        if (currentSong?.startFrom != 0) {
            mediaPlayer.seekTo(currentSong?.startFrom!!)
        }

        if (!sPlaybackSpeedPersisted) {
            currentPlaybackSpeed = 1.0F
        }

        if (isRepeat1X or isLooping or isPauseOnEnd) {
            isRepeat1X = false
            isLooping = false
            isPauseOnEnd = false
        }

        if (isSongFromPrefs) {
            if (goPreferences.isPreciseVolumeEnabled) {
                setPreciseVolume(currentVolumeInPercent)
            }
        }

        if (isQueue != null) {
            mediaPlayerInterface.onQueueStartedOrEnded(started = isQueueStarted)
        }

        updateMediaSessionMetaData()

        if (mExecutor == null) {
            startUpdatingCallbackWithPosition()
        }

        if (!::mAudioFocusRequestCompat.isInitialized) {
            initializeAudioFocusRequestCompat()
        }

        if (isPlay) {
            if (sFocusEnabled && mCurrentAudioFocusState != AUDIO_FOCUSED) {
                tryToGetAudioFocus()
            }
            play()
        }

        playerService.releaseWakeLock()

        // instantiate equalizer
        if (mediaPlayer.audioSessionId != AudioEffect.ERROR_BAD_VALUE) {
            if (EqualizerUtils.hasEqualizer(playerService.applicationContext)) {
                if (!sHasOpenedAudioEffects) {
                    sHasOpenedAudioEffects = true
                    EqualizerUtils.openAudioEffectSession(
                        playerService.applicationContext,
                        mediaPlayer.audioSessionId
                    )
                }
            } else {
                createCustomEqualizer()
            }
        }
    }

    private fun play() {
        startOrChangePlaybackSpeed()
        state = GoConstants.PLAYING
        updatePlaybackStatus(updateUI = true)
        startForeground()
    }

    private fun restoreCustomEqSettings() {
        mediaPlayer.run {
            val savedEqualizerSettings = goPreferences.savedEqualizerSettings

            savedEqualizerSettings?.let { (enabled, preset, bandSettings, bassBoost, virtualizer) ->

                setEqualizerEnabled(isEnabled = enabled)

                mEqualizer?.usePreset(preset.toShort())

                bandSettings?.iterator()?.withIndex()?.let { iterate ->
                    while (iterate.hasNext()) {
                        val item = iterate.next()
                        mEqualizer?.setBandLevel(
                            item.index.toShort(),
                            item.value.toInt().toShort()
                        )
                    }
                }

                mBassBoost?.setStrength(bassBoost)
                mVirtualizer?.setStrength(virtualizer)
            }
        }
    }

    fun openEqualizer(activity: Activity) {
        if (mEqualizer != null) {
            releaseCustomEqualizer()
            sHasOpenedAudioEffects = true
            EqualizerUtils.openAudioEffectSession(
                playerService.applicationContext,
                mediaPlayer.audioSessionId
            )
        }
        EqualizerUtils.openEqualizer(activity, mediaPlayer)
    }

    fun onOpenEqualizerCustom() {
        if (sHasOpenedAudioEffects) {
            EqualizerUtils.closeAudioEffectSession(
                playerService.applicationContext,
                mediaPlayer.audioSessionId
            )
            sHasOpenedAudioEffects = false
        }
    }

    fun release() {
        if (isMediaPlayer) {
            if (EqualizerUtils.hasEqualizer(playerService.applicationContext)) {
                EqualizerUtils.closeAudioEffectSession(
                    playerService.applicationContext,
                    mediaPlayer.audioSessionId
                )
            } else {
                releaseCustomEqualizer()
            }
            mediaPlayer.release()
            if (sFocusEnabled) {
                giveUpAudioFocus()
            }
            stopUpdatingCallbackWithPosition()
        }
        unregisterActionsReceiver()
    }

    private fun releaseCustomEqualizer() {
        if (mEqualizer != null) {
            mEqualizer?.release()
            mBassBoost?.release()
            mVirtualizer?.release()
            mEqualizer = null
            mBassBoost = null
            mVirtualizer = null
        }
    }

    fun resumeOrPause() {
        if (isPlaying) {
            pauseMediaPlayer()
        } else {
            if (isSongFromPrefs) {
                updateMediaSessionMetaData()
            }
            resumeMediaPlayer()
        }
    }

    private fun getRepeatMode() {
        var toastMessage = R.string.repeat_enabled
        when {
            isRepeat1X -> {
                isRepeat1X = false
                isLooping = true
                toastMessage = R.string.repeat_loop_enabled
            }
            isLooping -> {
                isLooping = false
                isPauseOnEnd = true
                toastMessage = R.string.repeat_disabled
                toastMessage.toToast(playerService)
                toastMessage = R.string.pause_on_end
            }
            isPauseOnEnd -> {
                isPauseOnEnd = false
                toastMessage = R.string.pause_on_end_disabled
            }
            else -> isRepeat1X = true
        }
        toastMessage.toToast(playerService)
    }

    fun repeat(updatePlaybackStatus: Boolean) {
        getRepeatMode()
        if (updatePlaybackStatus) {
            updatePlaybackStatus(updateUI = true)
        }
        mMusicNotificationManager.updateRepeatIcon()
    }

    fun setQueueEnabled(enabled: Boolean, canSkip: Boolean) {
        if (enabled) {
            mediaPlayerInterface.onQueueEnabled()
        } else {
            if (isQueue != null) {
                currentSong = isQueue
                isQueue = null
            }
            restoreQueueSong = null
            canRestoreQueue = false
            isQueueStarted = false
            goPreferences.isQueue = null
            mediaPlayerInterface.onQueueStartedOrEnded(started = false)
            if (canSkip) {
                skip(isNext = true)
            }
        }
    }

    fun skip(isNext: Boolean) {
        when {
            isQueue != null && !canRestoreQueue -> manageQueue(isNext = isNext)
            canRestoreQueue -> manageRestoredQueue()
            else -> {
                currentSong = getSkipSong(isNext = isNext)
                initMediaPlayer(currentSong)
            }
        }
    }

    fun fastSeek(isForward: Boolean) {

        val step = goPreferences.fastSeekingStep * 1000

        if (isMediaPlayer) {
            mediaPlayer.run {
                var newPosition = currentPosition
                if (isForward) {
                    newPosition += step
                } else {
                    newPosition -= step
                }
                seekTo(newPosition, updatePlaybackStatus = false, restoreProgressCallBack = false)
            }
        }
    }

    fun seekTo(position: Int, updatePlaybackStatus: Boolean, restoreProgressCallBack: Boolean) {
        if (isMediaPlayer) {
            mediaPlayer.setOnSeekCompleteListener { mp ->
                mp.setOnSeekCompleteListener(null)
                if (restoreProgressCallBack) {
                    startUpdatingCallbackWithPosition()
                }
                if (updatePlaybackStatus) {
                    updatePlaybackStatus(updateUI = !restoreProgressCallBack)
                }
            }
            mediaPlayer.seekTo(position)
        }
    }

    /**
     * Reconfigures the player according to audio focus settings and starts/restarts it. This method
     * starts/restarts the MediaPlayer instance respecting the current audio focus state. So if we
     * have focus, it will play normally; if we don't have focus, it will either leave the player
     * paused or set it to a low volume, depending on what is permitted by the current focus
     * settings.
     */
    private fun configurePlayerState() {
        when (mCurrentAudioFocusState) {
            AUDIO_NO_FOCUS_NO_DUCK -> pauseMediaPlayer()
            else -> {
                when (mCurrentAudioFocusState) {
                    AUDIO_NO_FOCUS_CAN_DUCK -> mediaPlayer.setVolume(VOLUME_DUCK, VOLUME_DUCK)
                    else -> mediaPlayer.setVolume(VOLUME_NORMAL, VOLUME_NORMAL)
                }
                // If we were playing when we lost focus, we need to resume playing.
                if (sPlayOnFocusGain) {
                    resumeMediaPlayer()
                    sPlayOnFocusGain = false
                    }
                }
            }
    }

    /* Sets the playback speed of the media player */
    @TargetApi(Build.VERSION_CODES.M)
    fun setPlaybackSpeed(speed: Float) {
        if (isMediaPlayer) {
            currentPlaybackSpeed = speed
            if (sPlaybackSpeedPersisted) {
                goPreferences.latestPlaybackSpeed = currentPlaybackSpeed
            }
            if (state != GoConstants.PAUSED) {
                updatePlaybackStatus(updateUI = false)
                mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(currentPlaybackSpeed)
            }
        }
    }

    /* Sets the volume of the media player */
    fun setPreciseVolume(percent: Int) {

        currentVolumeInPercent = percent

        if (isMediaPlayer) {
            fun volFromPercent(percent: Int): Float {
                if (percent == 100) {
                    return 1f
                }
                return (1 - (ln((101 - percent).toFloat()) / ln(101f)))
            }

            val new = volFromPercent(percent)
            mediaPlayer.setVolume(new, new)
        }
    }

    fun stopPlaybackService(stopPlayback: Boolean) {
        if (playerService.isRunning && isMediaPlayer && stopPlayback) {
            playerService.stopForeground(true)
            playerService.stopSelf()
        }
        mediaPlayerInterface.onClose()
    }

    private inner class PlayerBroadcastReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {

            try {
                intent.action?.let { act ->

                    when (act) {

                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (currentSong != null && goPreferences.isHeadsetPlugEnabled) {
                            pauseMediaPlayer()
                        }
                        BluetoothDevice.ACTION_ACL_CONNECTED -> if (currentSong != null && goPreferences.isHeadsetPlugEnabled) {
                            resumeMediaPlayer()
                        }

                        Intent.ACTION_MEDIA_BUTTON -> {
                            handleMediaButton(intent)
                        }

                        AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED ->
                            when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                                AudioManager.SCO_AUDIO_STATE_CONNECTED -> if (isCurrentSong && goPreferences.isHeadsetPlugEnabled) {
                                    resumeMediaPlayer()
                                }
                                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> if (isCurrentSong && goPreferences.isHeadsetPlugEnabled) {
                                    pauseMediaPlayer()
                                }
                            }

                        Intent.ACTION_HEADSET_PLUG -> if (isCurrentSong && goPreferences.isHeadsetPlugEnabled) {
                            when (intent.getIntExtra("state", -1)) {
                                // 0 means disconnected
                                HEADSET_DISCONNECTED -> if (isCurrentSong && goPreferences.isHeadsetPlugEnabled) {
                                    pauseMediaPlayer()
                                }
                                // 1 means connected
                                HEADSET_CONNECTED -> if (isCurrentSong && goPreferences.isHeadsetPlugEnabled) {
                                    resumeMediaPlayer()
                                }
                            }
                        }

                        AudioManager.ACTION_AUDIO_BECOMING_NOISY -> if (isPlaying && goPreferences.isHeadsetPlugEnabled) {
                            pauseMediaPlayer()
                        }
                    }
                }

                if (isOrderedBroadcast) {
                    abortBroadcast()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * Handles "media button" which is how external applications
         * comunicate with our app
         */
        private fun handleMediaButton(intent: Intent) {
            val event: KeyEvent =
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as KeyEvent? ?: return
            val action: Int = event.action
            var handled = false

            if (action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (isPlaying) {
                            pauseMediaPlayer()
                            handled = true
                        }
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        if (!isPlaying) {
                            resumeMediaPlayer()
                            handled = true
                        }
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        resumeOrPause()
                        handled = true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        if (isPlaying) {
                            skip(true)
                            handled = true
                        }
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        if (isPlaying) {
                            // Because this comes from an AI, we may want to
                            // not have the immediate reset because the user
                            // specifically requested the previous song
                            // for example by saying "previous song"
                            skip(false)
                            handled = true
                        }
                    }
                }
            }
            if (handled) {
                resultCode = Activity.RESULT_OK
                if (isOrderedBroadcast) {
                    abortBroadcast()
                }
            }
        }

    }
}
