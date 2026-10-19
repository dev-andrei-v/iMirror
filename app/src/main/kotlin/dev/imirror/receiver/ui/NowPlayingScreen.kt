package dev.imirror.receiver.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.imirror.receiver.R
import dev.imirror.receiver.airplay.NowPlayingInfo
import dev.imirror.receiver.util.Logger

/**
 * NowPlayingScreen — full-screen card shown while AirPlay audio plays without video (system audio
 * from a Mac, Apple Music, podcasts). Mirroring shows the video [StreamingScreen] instead; this
 * screen fills the otherwise-black surface for audio-only sessions.
 *
 * Layout (centered row): album art on the left (or an AirPlay-glyph placeholder when the sender
 * sends no artwork), and on the right the track title, artist, album, and a "♪ Audio from <sender>"
 * footer. The artwork takes a share of the screen height, so the card fits every TV. A 720p TV at
 * tvdpi is only 540 dp tall; a vertical column with a fixed 360 dp artwork overflowed it.
 */
class NowPlayingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val artwork: ImageView
    private val titleView: TextView
    private val artistView: TextView
    private val albumView: TextView
    private val senderView: TextView

    init {
        setBackgroundColor(color(R.color.background_dark))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        artwork = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ART_MAX_DP), dp(ART_MAX_DP))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(color(R.color.background_surface))
                cornerRadius = dp(16).toFloat()
            }
            clipToOutline = true
        }

        titleView = textView(TITLE_SP, R.color.text_primary, bold = true, lines = 2)
        artistView = textView(ARTIST_SP, R.color.text_secondary, lines = 1).apply {
            setPadding(0, dp(10), 0, 0)
        }
        albumView = textView(ALBUM_SP, R.color.text_tertiary, lines = 1).apply {
            setPadding(0, dp(4), 0, 0)
        }
        senderView = textView(SENDER_SP, R.color.protocol_airplay, lines = 1).apply {
            setPadding(0, dp(28), 0, 0)
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(TEXT_GAP_DP), 0, 0, 0)
            addView(titleView)
            addView(artistView)
            addView(albumView)
            addView(senderView)
        }

        row.addView(artwork)
        row.addView(textColumn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        addView(
            row,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )
    }

    /** Size the artwork and the text column from the real screen size, so the card always fits. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val art = (h * ART_HEIGHT_SHARE).toInt().coerceAtMost(dp(ART_MAX_DP))
        artwork.layoutParams = LinearLayout.LayoutParams(art, art)
        val room = w - art - dp(TEXT_GAP_DP) - dp(2 * EDGE_DP)
        val textWidth = (w * TEXT_WIDTH_SHARE).toInt().coerceAtMost(room).coerceAtLeast(dp(120))
        for (view in listOf(titleView, artistView, albumView, senderView)) view.maxWidth = textWidth
        requestLayout()
    }

    fun update(info: NowPlayingInfo) {
        val bitmap = info.artwork?.let { decodeArtwork(it) }
        if (bitmap != null) {
            artwork.scaleType = ImageView.ScaleType.CENTER_CROP
            artwork.setImageBitmap(bitmap)
            artwork.setColorFilter(null)
        } else {
            artwork.scaleType = ImageView.ScaleType.CENTER_INSIDE
            artwork.setImageResource(R.drawable.ic_airplay)
            artwork.setColorFilter(color(R.color.text_secondary))
            if (info.artwork != null) {
                Logger.w("NowPlayingScreen: artwork bytes (${info.artwork.size}B) failed to decode")
            }
        }
        titleView.text = info.title ?: context.getString(R.string.now_playing_audio)
        artistView.setTextVisible(info.artist)
        albumView.setTextVisible(info.album)
        senderView.text = context.getString(R.string.now_playing_from, info.senderName)
    }

    fun clear() {
        artwork.setImageDrawable(null)
    }

    /**
     * Decode the artwork near the size it is shown at. Senders push covers of 1500 px and more;
     * a full decode of one is 9 MB, which a 1 GB TV cannot spare for a card.
     */
    private fun decodeArtwork(bytes: ByteArray) = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = dp(ART_MAX_DP).coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun TextView.setTextVisible(value: String?) {
        if (value.isNullOrBlank()) {
            visibility = View.GONE
        } else {
            text = value
            visibility = View.VISIBLE
        }
    }

    private fun textView(sizeSp: Float, colorRes: Int, bold: Boolean = false, lines: Int) = TextView(context).apply {
        setTextColor(color(colorRes))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.START
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun color(res: Int): Int = try { context.getColor(res) } catch (_: Exception) { Color.WHITE }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        /** The artwork edge as a share of the screen height, capped at [ART_MAX_DP]. */
        private const val ART_HEIGHT_SHARE = 0.56f
        private const val ART_MAX_DP = 420
        /** The text column as a share of the screen width. */
        private const val TEXT_WIDTH_SHARE = 0.42f
        private const val TEXT_GAP_DP = 48
        private const val EDGE_DP = 48
        private const val TITLE_SP = 30f
        private const val ARTIST_SP = 22f
        private const val ALBUM_SP = 17f
        private const val SENDER_SP = 16f
    }
}
