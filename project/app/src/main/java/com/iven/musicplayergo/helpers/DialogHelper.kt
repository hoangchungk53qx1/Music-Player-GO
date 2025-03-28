package com.iven.musicplayergo.helpers

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.text.Spanned
import android.view.Gravity
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.text.parseAsHtml
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.LayoutMode
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.bottomsheets.BottomSheet
import com.afollestad.materialdialogs.bottomsheets.expandBottomSheet
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import com.iven.musicplayergo.R
import com.iven.musicplayergo.ui.ItemSwipeCallback
import com.iven.musicplayergo.adapters.FavoritesAdapter
import com.iven.musicplayergo.adapters.QueueAdapter
import com.iven.musicplayergo.extensions.*
import com.iven.musicplayergo.goPreferences
import com.iven.musicplayergo.models.Music
import com.iven.musicplayergo.ui.MediaControlInterface
import com.iven.musicplayergo.player.MediaPlayerHolder
import com.iven.musicplayergo.ui.ItemTouchCallback
import com.iven.musicplayergo.ui.UIControlInterface
import de.halfbit.edgetoedge.Edge
import de.halfbit.edgetoedge.edgeToEdge


object DialogHelper {

    @JvmStatic
    fun showQueueSongsDialog(
        context: Context,
        mediaPlayerHolder: MediaPlayerHolder
    ) = MaterialDialog(context, BottomSheet(LayoutMode.WRAP_CONTENT)).show {

        title(R.string.queue)
        val queueAdapter = QueueAdapter(context, this, mediaPlayerHolder)

        customView(R.layout.dialogs_music_container)
        val recyclerView = getCustomView().findViewById<RecyclerView>(R.id.dialogs_rv)
        recyclerView.adapter = queueAdapter

        ItemTouchHelper(ItemTouchCallback(queueAdapter.queueSongs, isActiveTabs = false))
            .attachToRecyclerView(recyclerView)

        ItemTouchHelper(ItemSwipeCallback(context, isQueueDialog = true, isFavoritesDialog = false) { viewHolder: RecyclerView.ViewHolder, _: Int ->
            if (!queueAdapter.performQueueSongDeletion(viewHolder.absoluteAdapterPosition)) {
                queueAdapter.notifyItemChanged(viewHolder.absoluteAdapterPosition)
            }
        }).attachToRecyclerView(recyclerView)


        if (ThemeHelper.isDeviceLand(context.resources)) {
            recyclerView.layoutManager = GridLayoutManager(context, 3)
        } else {
            if (VersioningHelper.isOreoMR1()) {
                window?.let { win ->
                    edgeToEdge {
                        recyclerView.fit { Edge.Bottom }
                        win.decorView.fit { Edge.Top }
                    }
                }
            }
        }

        recyclerView.post {
            if (mediaPlayerHolder.isQueueStarted) {
                val indexOfCurrentSong = mediaPlayerHolder.queueSongs.indexOf(mediaPlayerHolder.currentSong)
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                layoutManager.scrollToPositionWithOffset(indexOfCurrentSong, 0)
            }
            expandBottomSheet()
        }
    }

    @JvmStatic
    fun showClearQueueDialog(
        context: Context,
        mediaPlayerHolder: MediaPlayerHolder
    ) {

        MaterialDialog(context).show {

            title(R.string.queue)

            message(R.string.queue_songs_clear)

            positiveButton(R.string.yes) {
                goPreferences.isQueue = null
                goPreferences.queue = null
                with(mediaPlayerHolder) {
                    queueSongs.clear()
                    setQueueEnabled(enabled = false, canSkip = isQueueStarted)
                }
            }
            negativeButton(R.string.no)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @JvmStatic
    fun showFavoritesDialog(
        activity: Activity
    ): MaterialDialog = MaterialDialog(activity, BottomSheet(LayoutMode.WRAP_CONTENT)).show {

        title(R.string.favorites)

        val favoritesAdapter = FavoritesAdapter(
            activity,
            this
        )

        customView(R.layout.dialogs_music_container)
        val recyclerView = getCustomView().findViewById<RecyclerView>(R.id.dialogs_rv)
        recyclerView.adapter = favoritesAdapter

        if (ThemeHelper.isDeviceLand(context.resources)) {
            recyclerView.layoutManager = GridLayoutManager(context, 3)
        } else {
            if (VersioningHelper.isOreoMR1()) {
                window?.let { win ->
                    edgeToEdge {
                        recyclerView.fit { Edge.Bottom }
                        win.decorView.fit { Edge.Top }
                    }
                }
            }
        }

        ItemTouchHelper(ItemSwipeCallback(context, isQueueDialog = false, isFavoritesDialog = true) { viewHolder: RecyclerView.ViewHolder,
                                                            direction: Int ->
            if (direction == ItemTouchHelper.RIGHT) {
                favoritesAdapter.addFavoriteToQueue(viewHolder.absoluteAdapterPosition)
            } else {
                favoritesAdapter.performFavoriteDeletion(
                    viewHolder.absoluteAdapterPosition,
                    isSwipe = true
                )
            }
            favoritesAdapter.notifyDataSetChanged()
        }).attachToRecyclerView(recyclerView)
    }

    @JvmStatic
    fun showClearFavoritesDialog(
        activity: Activity
    ) {
        MaterialDialog(activity).show {

            title(R.string.favorites)

            message(R.string.favorites_clear)
            positiveButton(R.string.yes) {
                (activity as UIControlInterface).onFavoritesUpdated(clear = true)
            }
            negativeButton(R.string.no)
        }
    }

    @JvmStatic
    fun showPopupForHide(
        activity: Activity,
        itemView: View?,
        stringToFilter: String?
    ) {
        itemView?.let { view ->

            PopupMenu(activity, view).apply {

                inflate(R.menu.popup_filter)

                menu.findItem(R.id.music_container_title).setTitle(activity, stringToFilter)
                menu.enablePopupIcons(activity)
                gravity = Gravity.END

                setOnMenuItemClickListener {
                    (activity as UIControlInterface).onAddToFilter(stringToFilter)
                    return@setOnMenuItemClickListener true
                }
                show()
            }
        }
    }

    @JvmStatic
    fun showPopupForSongs(
        activity: Activity,
        itemView: View?,
        song: Music?,
        launchedBy: String
    ) {
        val mediaControlInterface = activity as MediaControlInterface
        itemView?.let { view ->

            PopupMenu(activity, view).apply {

                inflate(R.menu.popup_songs)

                menu.findItem(R.id.song_title).setTitle(activity, song?.title)
                menu.enablePopupIcons(activity)
                gravity = Gravity.END

                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.favorites_add -> {
                            ListsHelper.addToFavorites(
                                activity,
                                song,
                                canRemove = false,
                                0,
                                launchedBy
                            )
                            mediaControlInterface.onFavoriteAddedOrRemoved()
                        }
                        else -> mediaControlInterface.onAddToQueue(song)
                    }
                    return@setOnMenuItemClickListener true
                }
                show()
            }
        }
    }

    @JvmStatic
    fun showPopupForPlaybackSpeed(
        activity: Activity,
        view: View
    ) {

        PopupMenu(activity, view).apply {
            inflate(R.menu.popup_speed)
            gravity = Gravity.END

            if (goPreferences.isPlaybackSpeedPersisted) {
                menu.findItem(getSelectedPlaybackItem(goPreferences.latestPlaybackSpeed)).setTitleColor(ThemeHelper.resolveThemeAccent(activity))
            }

            setOnMenuItemClickListener { menuItem ->
                val playbackSpeed = when (menuItem.itemId) {
                    R.id.speed_0 -> 0.25F
                    R.id.speed_1 -> 0.5F
                    R.id.speed_2 -> 0.75F
                    R.id.speed_3 -> 1.0F
                    R.id.speed_4 -> 1.25F
                    R.id.speed_5 -> 1.5F
                    R.id.speed_6 -> 1.75F
                    R.id.speed_7 -> 2.0F
                    R.id.speed_8 -> 2.5F
                    else -> 2.5F
                }
                if (goPreferences.isPlaybackSpeedPersisted) {
                    menu.findItem(getSelectedPlaybackItem(playbackSpeed)).setTitleColor(ThemeHelper.resolveThemeAccent(activity))
                }
                (activity as MediaControlInterface).onChangePlaybackSpeed(playbackSpeed)
                return@setOnMenuItemClickListener true
            }
            show()
        }
    }

    private fun getSelectedPlaybackItem(playbackSpeed: Float) = when (playbackSpeed) {
        0.25F -> R.id.speed_0
        0.5F -> R.id.speed_1
        0.75F -> R.id.speed_2
        1.0F -> R.id.speed_3
        1.25F -> R.id.speed_4
        1.5F -> R.id.speed_5
        1.75F -> R.id.speed_6
        2.0F -> R.id.speed_7
        2.25F -> R.id.speed_8
        else -> R.id.speed_9
    }

    @JvmStatic
    fun stopPlaybackDialog(
        context: Context,
        mediaPlayerHolder: MediaPlayerHolder
    ) {

        MaterialDialog(context).show {

            title(R.string.app_name)

            message(R.string.on_close_activity)
            positiveButton(R.string.yes) {
                mediaPlayerHolder.stopPlaybackService(stopPlayback = true)
            }
            negativeButton(R.string.no) {
                mediaPlayerHolder.stopPlaybackService(stopPlayback = false)
            }
        }
    }

    @JvmStatic
    fun computeDurationText(ctx: Context, favorite: Music?): Spanned? {
        if (favorite?.startFrom != null && favorite.startFrom > 0L) {
            return ctx.getString(
                R.string.favorite_subtitle,
                favorite.startFrom.toLong().toFormattedDuration(
                    isAlbum = false,
                    isSeekBar = false
                ),
                favorite.duration.toFormattedDuration(isAlbum = false, isSeekBar = false)
            ).parseAsHtml()
        }
        return favorite?.duration?.toFormattedDuration(isAlbum = false, isSeekBar = false)
            ?.parseAsHtml()
    }
}
