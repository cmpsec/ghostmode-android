package com.today.td

import android.accounts.AccountManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.ViewGroup.LayoutParams
import android.view.ViewOutlineProvider
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

sealed class Row {
    abstract val stableId: String

    data class Section(
        val id: Int,
        val title: String,
        val count: String,
        val itemCount: Int,
        val subtitle: String?,
        val accent: Int,
        val selectable: Boolean,
        val selected: Int,
        val allSelected: Boolean,
        val busy: Boolean
    ) : Row() {
        override val stableId = "sec$id"
    }

    data class Check(
        val kind: Kind,
        val id: String,
        val title: String,
        val subtitle: String,
        val selected: Boolean,
        val iconMode: IconMode,
        val iconKey: String
    ) : Row() {
        override val stableId = "c$kind$id"
    }

    data class Usage(
        val packageName: String,
        val title: String,
        val subtitle: String,
        val status: String,
        val statusColor: Int,
        val statusBg: Int,
        val clearEnabled: Boolean
    ) : Row() {
        override val stableId = "u$packageName"
    }

    data class Chrome(val detail: String, val enabled: Boolean) : Row() {
        override val stableId = "chrome"
    }

    data class History(
        val title: String,
        val url: String,
        val time: String
    ) : Row() {
        override val stableId = "h$url"
    }

    data class Clip(val detail: String, val result: String?, val enabled: Boolean) : Row() {
        override val stableId = "clip"
    }

    data class Empty(val section: Int, val text: String) : Row() {
        override val stableId = "e$section"
    }
}

class TodayAdapter(private val callbacks: Callbacks) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {
    interface Callbacks {
        fun onToggle(kind: Kind, id: String)
        fun onToggleAll(section: Int)
        fun onDelete(section: Int)
        fun onChrome()
        fun onShowChrome()
        fun onRevealAccounts()
        fun onCopy()
        fun onClearUsage(packageName: String)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Row.Section -> 1
        is Row.Check -> 2
        is Row.Usage -> 3
        is Row.Chrome -> 4
        is Row.Clip -> 5
        is Row.Empty -> 6
        is Row.History -> 7
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            1 -> SectionHolder(inflater.inflate(R.layout.row_section, parent, false))
            2 -> CheckHolder(inflater.inflate(R.layout.row_check, parent, false))
            3 -> UsageHolder(inflater.inflate(R.layout.row_usage, parent, false))
            4 -> ChromeHolder(inflater.inflate(R.layout.row_chrome, parent, false))
            5 -> ClipHolder(inflater.inflate(R.layout.row_clip, parent, false))
            7 -> HistoryHolder(inflater.inflate(R.layout.row_history, parent, false))
            else -> EmptyHolder(inflater.inflate(R.layout.row_empty, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Section -> (holder as SectionHolder).bind(row, callbacks)
            is Row.Check -> (holder as CheckHolder).bind(row, callbacks)
            is Row.Usage -> (holder as UsageHolder).bind(row, callbacks)
            is Row.Chrome -> (holder as ChromeHolder).bind(row, callbacks)
            is Row.Clip -> (holder as ClipHolder).bind(row, callbacks)
            is Row.History -> (holder as HistoryHolder).bind(row)
            is Row.Empty -> (holder as EmptyHolder).bind(row, callbacks)
        }
    }

    private class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val accent: View = view.findViewById(R.id.accent)
        private val title: TextView = view.findViewById(R.id.title)
        private val count: TextView = view.findViewById(R.id.count)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val actions: View = view.findViewById(R.id.actions)
        private val selectAll: CheckBox = view.findViewById(R.id.selectAll)
        private val delete: TextView = view.findViewById(R.id.delete)

        fun bind(row: Row.Section, callbacks: Callbacks) {
            accent.setBackgroundColor(row.accent)
            title.text = row.title
            count.text = row.count
            if (row.subtitle.isNullOrBlank()) {
                subtitle.visibility = View.GONE
            } else {
                subtitle.visibility = View.VISIBLE
                subtitle.text = row.subtitle
            }
            actions.visibility = if (row.selectable) View.VISIBLE else View.GONE
            selectAll.setOnCheckedChangeListener(null)
            selectAll.isChecked = row.allSelected
            selectAll.isEnabled = !row.busy && row.itemCount > 0
            selectAll.setOnClickListener {
                if (!row.busy) callbacks.onToggleAll(row.id)
            }
            delete.text = if (row.selected > 0) "Delete (${row.selected})" else "Delete"
            delete.isEnabled = !row.busy && row.selected > 0
            delete.setOnClickListener {
                if (!row.busy && row.selected > 0) callbacks.onDelete(row.id)
            }
        }
    }

    private class CheckHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val check: CheckBox = view.findViewById(R.id.check)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)

        fun bind(row: Row.Check, callbacks: Callbacks) {
            check.setOnCheckedChangeListener(null)
            check.isChecked = row.selected
            title.text = row.title
            subtitle.text = row.subtitle
            icon.contentDescription = row.title
            bindIcon(icon, row.iconMode, row.iconKey, row.title)
            itemView.setOnClickListener { callbacks.onToggle(row.kind, row.id) }
        }
    }

    private class UsageHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val status: TextView = view.findViewById(R.id.status)
        private val clear: TextView = view.findViewById(R.id.clear)

        fun bind(row: Row.Usage, callbacks: Callbacks) {
            title.text = row.title
            subtitle.text = row.subtitle
            status.text = row.status
            status.setTextColor(row.statusColor)
            status.background = GradientDrawable().apply {
                cornerRadius = 24f * itemView.resources.displayMetrics.density
                setColor(row.statusBg)
            }
            clear.isEnabled = row.clearEnabled
            clear.setOnClickListener {
                if (row.clearEnabled) callbacks.onClearUsage(row.packageName)
            }
            icon.contentDescription = row.title
            bindIcon(icon, IconMode.APP, row.packageName, row.title)
        }
    }

    private class ChromeHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val detail: TextView = view.findViewById(R.id.detail)
        private val showHistory: TextView = view.findViewById(R.id.showHistory)
        private val action: TextView = view.findViewById(R.id.action)

        fun bind(row: Row.Chrome, callbacks: Callbacks) {
            detail.text = row.detail
            showHistory.isEnabled = row.enabled
            action.isEnabled = row.enabled
            showHistory.setOnClickListener { if (row.enabled) callbacks.onShowChrome() }
            action.setOnClickListener { if (row.enabled) callbacks.onChrome() }
        }
    }

    private class ClipHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val detail: TextView = view.findViewById(R.id.detail)
        private val result: TextView = view.findViewById(R.id.result)
        private val action: TextView = view.findViewById(R.id.action)

        fun bind(row: Row.Clip, callbacks: Callbacks) {
            detail.text = row.detail
            if (row.result.isNullOrBlank()) {
                result.visibility = View.GONE
            } else {
                result.visibility = View.VISIBLE
                result.text = row.result
            }
            action.isEnabled = row.enabled
            action.setOnClickListener { if (row.enabled) callbacks.onCopy() }
        }
    }

    private class HistoryHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.title)
        private val url: TextView = view.findViewById(R.id.url)
        private val time: TextView = view.findViewById(R.id.time)

        fun bind(row: Row.History) {
            title.text = row.title
            url.text = row.url
            time.text = row.time
        }
    }

    private class EmptyHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.text)
        fun bind(row: Row.Empty, callbacks: Callbacks) {
            text.text = row.text
            itemView.setOnClickListener {
                if (row.section == SEC_ACCOUNTS) callbacks.onRevealAccounts()
            }
        }
    }

    companion object {
        private val io = Executors.newFixedThreadPool(2)
        private val main = Handler(Looper.getMainLooper())
        private val cache = object : LruCache<String, Drawable>(24 * 1024) {
            override fun sizeOf(key: String, value: Drawable): Int {
                val bitmap = (value as? BitmapDrawable)?.bitmap
                return if (bitmap != null) (bitmap.byteCount / 1024).coerceAtLeast(1) else 1
            }
        }
        private val palette = intArrayOf(
            0xFF2F3336.toInt(),
            0xFF1D9BF0.toInt(),
            0xFF536471.toInt(),
            0xFF16181C.toInt()
        )
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.stableId == newItem.stableId
            override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
        }

        private fun bindIcon(icon: ImageView, mode: IconMode, key: String, title: String) {
            val pad = (4 * icon.resources.displayMetrics.density).toInt()
            sizeIcon(icon, 40)
            icon.clipToOutline = false
            icon.background = null
            icon.outlineProvider = ViewOutlineProvider.BOUNDS
            when (mode) {
                IconMode.FILE -> {
                    icon.scaleType = ImageView.ScaleType.FIT_CENTER
                    icon.setPadding(pad, pad, pad, pad)
                    icon.setImageResource(R.drawable.ic_file)
                }
                IconMode.FOLDER -> {
                    icon.scaleType = ImageView.ScaleType.FIT_CENTER
                    icon.setPadding(pad, pad, pad, pad)
                    icon.setImageResource(R.drawable.ic_folder)
                }
                IconMode.MEDIA -> {
                    val frame = (2 * icon.resources.displayMetrics.density).toInt()
                    val radius = 6f * icon.resources.displayMetrics.density
                    icon.scaleType = ImageView.ScaleType.CENTER_CROP
                    icon.setPadding(frame, frame, frame, frame)
                    icon.setBackgroundResource(R.drawable.bg_thumb)
                    icon.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, radius)
                        }
                    }
                    icon.clipToOutline = true
                    val cacheKey = "media:$key"
                    val cached = cache.get(cacheKey)
                    if (cached != null) {
                        icon.setImageDrawable(copy(cached))
                        return
                    }
                    icon.setImageResource(R.drawable.bg_placeholder)
                    icon.tag = cacheKey
                    val appContext = icon.context.applicationContext
                    io.execute {
                        val drawable = loadThumb(appContext, key)
                        if (drawable != null) cache.put(cacheKey, drawable)
                        main.post {
                            if (icon.tag == cacheKey && drawable != null) {
                                icon.setImageDrawable(copy(drawable))
                            }
                        }
                    }
                }
                IconMode.APP, IconMode.ACCOUNT -> {
                    icon.scaleType = ImageView.ScaleType.FIT_CENTER
                    icon.setPadding(0, 0, 0, 0)
                    val cacheKey = mode.name + ":" + key + ":" + title
                    val cached = cache.get(cacheKey)
                    if (cached != null) {
                        icon.setImageDrawable(copy(cached))
                        return
                    }
                    icon.setImageResource(R.drawable.bg_placeholder)
                    icon.tag = cacheKey
                    val appContext = icon.context.applicationContext
                    io.execute {
                        val drawable = loadIcon(appContext, mode, key, title)
                        if (drawable != null) cache.put(cacheKey, drawable)
                        main.post {
                            if (icon.tag == cacheKey && drawable != null) {
                                icon.setImageDrawable(copy(drawable))
                            }
                        }
                    }
                }
            }
        }

        private fun sizeIcon(icon: ImageView, dp: Int) {
            val px = (dp * icon.resources.displayMetrics.density).toInt()
            val params = icon.layoutParams ?: LayoutParams(px, px)
            if (params.width != px || params.height != px) {
                params.width = px
                params.height = px
                icon.layoutParams = params
            }
        }

        private fun loadThumb(context: Context, path: String): Drawable? {
            return try {
                val bitmap = if (isVideoName(path)) videoThumb(path) else imageThumb(path)
                if (bitmap == null) null else BitmapDrawable(context.resources, bitmap)
            } catch (e: Exception) {
                null
            }
        }

        private fun imageThumb(path: String): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            while (largest / sample > 512) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeFile(path, opts) ?: return null
            val rotated = rotateImage(path, decoded)
            val thumb = ThumbnailUtils.extractThumbnail(rotated, 192, 192)
            if (rotated !== thumb && !rotated.isRecycled) rotated.recycle()
            if (decoded !== rotated && decoded !== thumb && !decoded.isRecycled) decoded.recycle()
            return thumb
        }

        private fun rotateImage(path: String, bitmap: Bitmap): Bitmap {
            return try {
                val exif = ExifInterface(path)
                val degrees = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> return bitmap
                }
                val matrix = Matrix()
                matrix.postRotate(degrees)
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } catch (e: Exception) {
                bitmap
            }
        }

        @Suppress("DEPRECATION")
        private fun videoThumb(path: String): Bitmap? {
            val bitmap = if (Build.VERSION.SDK_INT >= 29) {
                ThumbnailUtils.createVideoThumbnail(File(path), Size(192, 192), null)
            } else {
                ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
            } ?: return null
            val thumb = ThumbnailUtils.extractThumbnail(bitmap, 192, 192)
            if (thumb !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            return thumb
        }

        private fun loadIcon(context: Context, mode: IconMode, key: String, title: String): Drawable? {
            return try {
                when (mode) {
                    IconMode.APP -> context.packageManager.getApplicationIcon(key)
                    IconMode.ACCOUNT -> authenticatorIcon(context, key) ?: letterDrawable(context, title, key)
                    else -> null
                }
            } catch (e: Exception) {
                if (mode == IconMode.ACCOUNT) letterDrawable(context, title, key) else null
            }
        }

        private fun authenticatorIcon(context: Context, key: String): Drawable? {
            if (key.isBlank()) return null
            val types = AccountManager.get(context).authenticatorTypes
            val desc = types.firstOrNull { it.packageName == key || it.type == key } ?: return null
            if (desc.iconId == 0) return null
            val resources = context.packageManager.getResourcesForApplication(desc.packageName)
            return resources.getDrawable(desc.iconId, null)
        }

        private fun letterDrawable(context: Context, title: String, key: String): Drawable {
            val letter = title.trim().firstOrNull()?.toString() ?: "?"
            val color = palette[(key.hashCode() and Int.MAX_VALUE) % palette.size]
            val size = 128
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = color
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = 64f
            val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letter, size / 2f, y, paint)
            return BitmapDrawable(context.resources, bitmap)
        }

        private fun copy(drawable: Drawable): Drawable {
            return drawable.constantState?.newDrawable()?.mutate() ?: drawable
        }
    }
}
