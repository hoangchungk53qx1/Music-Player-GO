package com.iven.musicplayergo.extensions

import android.content.ContentUris
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import coil.Coil
import coil.request.ImageRequest
import com.iven.musicplayergo.GoConstants
import com.iven.musicplayergo.R
import com.iven.musicplayergo.goPreferences
import com.iven.musicplayergo.models.Music
import com.iven.musicplayergo.player.MediaPlayerHolder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern


fun MediaPlayerHolder.startSongFromQueue(song: Music?) {
    if (canRestoreQueue) {
        canRestoreQueue = false
    }
    if (isSongFromPrefs) {
        isSongFromPrefs = false
    }
    if (!isPlay) {
        isPlay = true
    }
    if (!isQueueStarted) {
        isQueueStarted = true
    }
    currentSong = song
    initMediaPlayer(currentSong)
}

fun MediaPlayerHolder.setCanRestoreQueue() {
    if (!canRestoreQueue) {
        canRestoreQueue = isQueue == null && !isQueueStarted && queueSongs.isNotEmpty()
    }
    if (isQueue == null) {
        isQueue = currentSong
        setQueueEnabled(enabled = true, canSkip = false)
    }
}

fun MediaPlayerHolder.addSongsToNextQueuePosition(songsToQueue: List<Music>) {
    if (isQueue != null && !canRestoreQueue && isQueueStarted) {
        val currentPosition = queueSongs.indexOf(currentSong)
        queueSongs.addAll(currentPosition+1, songsToQueue)
    } else {
        queueSongs.addAll(songsToQueue)
    }
}

//https://codereview.stackexchange.com/a/97819
fun String?.toFilenameWithoutExtension() = try {
    Pattern.compile("(?<=.)\\.[^.]+$").matcher(this!!).replaceAll("")
} catch (e: Exception) {
    e.printStackTrace()
    this
}

fun Long.toContentUri(): Uri = ContentUris.withAppendedId(
    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
    this
)

fun Uri.toBitrate(context: Context): Pair<Int, Int>? {
    val mediaExtractor = MediaExtractor()
    return try {

        mediaExtractor.setDataSource(context, this, null)

        val mediaFormat = mediaExtractor.getTrackFormat(0)

        val sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        // Get bitrate in bps, divide by 1000 to get Kbps
        val bitrate = mediaFormat.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
        Pair(first = sampleRate, second = bitrate)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } finally {
        mediaExtractor.release()
    }
}

fun Long.toAlbumArtURI(): Uri {
    return ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), this)
}

fun Long.waitForCover(context: Context, canLoadDefault: Boolean, onDone: (Bitmap?) -> Unit) {
    val defaultAlbumArt = ContextCompat.getDrawable(context, R.drawable.album_art)?.toBitmap()
    Coil.imageLoader(context).enqueue(
        ImageRequest.Builder(context)
            .data(toAlbumArtURI())
            .target(
                onSuccess = { onDone(it.toBitmap()) },
                onError = { onDone(if (canLoadDefault) {
                    defaultAlbumArt
                } else {
                    null
                }) }
            )
            .build()
    )
}

fun Long.toFormattedDuration(isAlbum: Boolean, isSeekBar: Boolean) = try {

    val defaultFormat = if (isAlbum) {
        "%02dm:%02ds"
    } else {
        "%02d:%02d"
    }

    val hours = TimeUnit.MILLISECONDS.toHours(this)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(this)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(this)

    if (minutes < 60) {
        String.format(
            Locale.getDefault(), defaultFormat,
            minutes,
            seconds - TimeUnit.MINUTES.toSeconds(minutes)
        )
    } else {
        // https://stackoverflow.com/a/9027379
        when {
            isSeekBar -> String.format(
                "%02d:%02d:%02d",
                hours,
                minutes - TimeUnit.HOURS.toMinutes(hours),
                seconds - TimeUnit.MINUTES.toSeconds(minutes)
            )
            else -> String.format(
                "%02dh:%02dm",
                hours,
                minutes - TimeUnit.HOURS.toMinutes(hours)
            )
        }
    }

} catch (e: Exception) {
    e.printStackTrace()
    ""
}

fun Int.toFormattedDate(): String {
    return try {
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val netDate = Date(this.toLong() * 1000)
        sdf.format(netDate)
    } catch (e: Exception) {
        e.printStackTrace()
        ""
    }
}

fun Int.toFormattedTrack() = try {
    if (this >= 1000) {
        this % 1000
    } else {
        this
    }
} catch (e: Exception) {
    e.printStackTrace()
    0
}

fun Int.toFormattedYear(resources: Resources) =
    if (this != 0) {
        toString()
    } else {
        resources.getString(R.string.unknown_year)
    }

fun Music.toSavedMusic(playerPosition: Int, savedLaunchedBy: String) =
    Music(
        artist,
        year,
        track,
        title,
        displayName,
        duration,
        album,
        albumId,
        relativePath,
        id,
        savedLaunchedBy,
        playerPosition,
        dateAdded
    )

fun List<Music>.savedSongIsAvailable(currentSong: Music?) : Music? =
    find { currentSong?.title == it.title && currentSong?.displayName == it.displayName && currentSong?.track == it.track && currentSong.albumId == it.albumId && currentSong.album == it.album }

fun Music?.toName(): String? = if (goPreferences.songsVisualization == GoConstants.FN) {
    this?.displayName?.toFilenameWithoutExtension()
} else {
    this?.title
}
