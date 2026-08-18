package com.ambient.tvclock.grainstorm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ambient.tvclock.BackgroundPreferences
import com.ambient.tvclock.R
import java.util.concurrent.Executors

/**
 * Browse the wallpaper library and pick one, from the couch.
 *
 * D-pad first: every cell is focusable, the focus ring is a solid border that
 * reads across a room, and OK sets the wallpaper. Pressing OK talks to the
 * server and then nudges the background source, so the change is visible
 * immediately rather than at the next poll.
 *
 * Plain Views and a RecyclerView, matching the adapters the rest of the app
 * uses (`QueueTrackAdapter`, `PlaylistAdapter`). No Compose, no new deps.
 */
class WallpaperPickerActivity : AppCompatActivity() {

    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private lateinit var message: TextView

    private lateinit var repository: WallpaperRepository
    private lateinit var thumbnails: ThumbnailLoader
    private lateinit var adapter: WallpaperAdapter

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wallpaper-picker").apply { isDaemon = true }
    }

    private var nextCursor: String? = null
    private var loading = false
    private var exhausted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallpaper_picker)

        grid = findViewById(R.id.wallpaperGrid)
        status = findViewById(R.id.wallpaperPickerStatus)
        message = findViewById(R.id.wallpaperPickerMessage)

        repository = WallpaperRepository(this)
        thumbnails = ThumbnailLoader(repository.authHeader())
        adapter = WallpaperAdapter(thumbnails, repository, ::apply)

        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        grid.adapter = adapter
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val manager = recyclerView.layoutManager as GridLayoutManager
                // Fetch the next page a row early so D-pad travel never stalls
                // at the bottom edge waiting on the network.
                if (manager.findLastVisibleItemPosition() >= adapter.itemCount - COLUMNS) loadNextPage()
            }
        })

        status.text = repository.serverUrl()
        loadNextPage()
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbnails.shutdown()
        io.shutdownNow()
    }

    private fun loadNextPage() {
        if (loading || exhausted) return
        loading = true
        if (adapter.itemCount == 0) showMessage(getString(R.string.wallpaper_picker_loading))
        val cursor = nextCursor
        io.execute {
            val result = repository.listAssets(cursor = cursor)
            runOnUiThread {
                loading = false
                when (result) {
                    is GrainstormClient.Result.Ok -> {
                        val page = result.value
                        nextCursor = page.nextCursor
                        exhausted = page.nextCursor == null
                        adapter.append(page.assets)
                        if (adapter.itemCount == 0) {
                            showMessage(getString(R.string.wallpaper_picker_empty))
                        } else {
                            showGrid()
                        }
                    }
                    is GrainstormClient.Result.Err -> {
                        exhausted = true
                        if (adapter.itemCount == 0) {
                            // Offline with nothing loaded: say so plainly and
                            // stop, rather than leaving a spinner running.
                            showMessage(
                                getString(R.string.wallpaper_picker_offline) + "\n\n" +
                                    WallpaperSettings.describe(this, result.failure)
                            )
                        } else {
                            status.text = WallpaperSettings.describe(this, result.failure)
                        }
                    }
                    is GrainstormClient.Result.NotModified -> exhausted = true
                }
            }
        }
    }

    private fun apply(asset: SyncContract.Asset) {
        status.text = getString(R.string.wallpaper_picker_applying)
        io.execute {
            val result = repository.setCurrent(asset.id)
            runOnUiThread {
                when (result) {
                    is GrainstormClient.Result.Ok -> {
                        adapter.markCurrent(asset.id)
                        status.text = getString(R.string.wallpaper_picker_applied)
                        Toast.makeText(this, R.string.wallpaper_picker_applied, Toast.LENGTH_SHORT).show()
                        // Nudge the dashboard's background source to pick the
                        // new wallpaper up now instead of at its next poll.
                        BackgroundPreferences.pulseShuffleSignal(this)
                    }
                    is GrainstormClient.Result.Err ->
                        status.text = WallpaperSettings.describe(this, result.failure)
                    is GrainstormClient.Result.NotModified -> Unit
                }
            }
        }
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
        grid.visibility = View.GONE
    }

    private fun showGrid() {
        message.visibility = View.GONE
        grid.visibility = View.VISIBLE
    }

    private class WallpaperAdapter(
        private val thumbnails: ThumbnailLoader,
        private val repository: WallpaperRepository,
        private val onPick: (SyncContract.Asset) -> Unit,
    ) : RecyclerView.Adapter<WallpaperAdapter.Holder>() {

        private val items = mutableListOf<SyncContract.Asset>()
        private var currentId: String? = null

        fun append(more: List<SyncContract.Asset>) {
            if (more.isEmpty()) return
            val from = items.size
            items += more
            notifyItemRangeInserted(from, more.size)
        }

        fun markCurrent(id: String) {
            // Only two cells can change: the one losing the badge and the one
            // gaining it. Rebinding the whole grid would also re-request every
            // visible thumbnail.
            val previous = items.indexOfFirst { it.id == currentId }
            currentId = id
            if (previous >= 0) notifyItemChanged(previous)
            val next = items.indexOfFirst { it.id == id }
            if (next >= 0) notifyItemChanged(next)
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_wallpaper, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val asset = items[position]
            val context = holder.itemView.context

            holder.seed.text = if (asset.seed >= 0) {
                context.getString(R.string.wallpaper_picker_seed, asset.seed)
            } else {
                asset.id
            }

            val preview = asset.previewRendition()
            val largest = asset.largestRendition()
            holder.size.text = when {
                asset.id == currentId -> context.getString(R.string.wallpaper_picker_current)
                largest != null ->
                    context.getString(R.string.wallpaper_picker_size, largest.width, largest.height)
                else -> ""
            }

            if (preview != null) {
                thumbnails.load(repository.absoluteUrl(preview.url), holder.thumb)
            } else {
                holder.thumb.setImageDrawable(null)
            }

            holder.itemView.setOnClickListener { onPick(asset) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.wallpaperThumb)
            val seed: TextView = view.findViewById(R.id.wallpaperSeed)
            val size: TextView = view.findViewById(R.id.wallpaperSize)
        }
    }

    companion object {
        /** Four across fills a 16:9 panel at a size that still reads from a
         *  sofa without the grid becoming a wall of stamps. */
        private const val COLUMNS = 4
    }
}
