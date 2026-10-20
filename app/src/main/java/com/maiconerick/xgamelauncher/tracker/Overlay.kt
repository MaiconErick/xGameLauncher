package com.maiconerick.xgamelauncher.tracker

import coil.executeBlocking
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Session banner drawn OVER the running game ("Now tracking", "Session ended", "Lid
 * closed"…): an opaque neon card with the game's box art (or the emulator icon), an accent
 * caption, the game name, a detail line, and an accent bar that drains while it's shown.
 * No-op if SYSTEM_ALERT_WINDOW isn't granted — the sound cue and notification still fire.
 */
object Overlay {

    fun show(
        ctx: Context,
        kicker: String,
        title: String,
        subtitle: String,
        accentArgb: Int,
        artPath: String? = null,
        iconPkg: String? = null,
        ms: Long = 3600,
    ) {
        if (!Settings.canDrawOverlays(ctx)) return
        val app = ctx.applicationContext
        // Art can be a URL / content uri (Coil, possibly cached) or missing: resolve it off
        // the main thread, then build the view on it.
        Thread {
            val art = loadArt(app, artPath, title, iconPkg)
            Handler(Looper.getMainLooper()).post { showOnMain(app, kicker, title, subtitle, accentArgb, art, ms) }
        }.start()
    }

    private fun showOnMain(app: Context, kicker: String, title: String, subtitle: String, accentArgb: Int, art: Bitmap?, ms: Long) {
        run {
            try {
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val (view, bar) = buildView(app, kicker, title, subtitle, accentArgb, art)
                val type = if (Build.VERSION.SDK_INT >= 26)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    // Touchable on purpose: Android 12+ caps pass-through (NOT_TOUCHABLE)
                    // overlays at 80% opacity. NOT_TOUCH_MODAL lets touches outside the
                    // card through; tapping the card dismisses it.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                )
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.y = dp(app, 40)
                view.setOnClickListener { try { wm.removeView(view) } catch (_: Exception) {} }
                wm.addView(view, lp)
                view.translationY = -dp(app, 24).toFloat()
                view.animate().alpha(1f).translationY(0f).setDuration(240).start()
                // Drain the accent bar over the display time.
                bar.pivotX = 0f
                ObjectAnimator.ofFloat(bar, "scaleX", 1f, 0f).setDuration(ms).start()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        view.animate().alpha(0f).translationY(-dp(app, 16).toFloat()).setDuration(260)
                            .withEndAction { try { wm.removeView(view) } catch (_: Exception) {} }
                            .start()
                    } catch (_: Exception) { try { wm.removeView(view) } catch (_: Exception) {} }
                }, ms)
            } catch (_: Exception) {}
        }
    }

    private fun buildView(
        ctx: Context, kicker: String, title: String, subtitle: String, accent: Int,
        art: Bitmap?,
    ): Pair<View, View> {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(Color.parseColor("#FF0A0F14"))   // fully opaque
                setStroke(dp(ctx, 2), accent)
            }
            clipToOutline = true
            alpha = 0f
            elevation = dp(ctx, 8).toFloat()
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 22), dp(ctx, 12))
        }
        // Game art (box art) or the emulator/app icon.
        if (art != null) {
            val frame = FrameLayout(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(ctx, 8).toFloat(); setColor(Color.BLACK); setStroke(dp(ctx, 1), accent)
                }
                clipToOutline = true
            }
            frame.addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(art)
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            row.addView(frame, LinearLayout.LayoutParams(dp(ctx, 50), dp(ctx, 66)).apply { rightMargin = dp(ctx, 14) })
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = kicker.uppercase(); setTextColor(accent); textSize = 10.5f
            letterSpacing = 0.18f; typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(ctx).apply {
            text = title; setTextColor(Color.WHITE); textSize = 17f
            typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(ctx, 360)
        })
        col.addView(TextView(ctx).apply {
            text = subtitle; setTextColor(Color.parseColor("#C8D0D6")); textSize = 12.5f
            maxLines = 2; maxWidth = dp(ctx, 360)
        })
        row.addView(col)
        card.addView(row)
        val bar = View(ctx).apply { setBackgroundColor(accent) }
        card.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 3)))
        return card to bar
    }

    /** Box art (file, URL or content uri — via Coil), else art found by the game's name,
     *  else the package's launcher icon. Runs off the main thread. */
    private fun loadArt(ctx: Context, artPath: String?, gameName: String, iconPkg: String?): Bitmap? {
        val ref = artPath?.takeIf { it.isNotBlank() }
            ?: runCatching { kotlinx.coroutines.runBlocking { com.maiconerick.xgamelauncher.bottom.artForName(ctx, gameName) } }.getOrNull()
        ref?.takeIf { it.startsWith("http") || it.startsWith("content:") }?.let { url ->
            runCatching {
                val req = coil.request.ImageRequest.Builder(ctx).data(url).size(400).allowHardware(false).build()
                ((coil.Coil.imageLoader(ctx).executeBlocking(req) as? coil.request.SuccessResult)?.drawable
                    as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }.getOrNull()?.let { return it }
        }
        ref?.takeIf { !it.startsWith("http") && !it.startsWith("content:") }?.let { p ->
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(p, o)
                var sample = 1
                while (o.outHeight / sample > 400) sample *= 2
                BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()?.let { return it }
        }
        iconPkg?.let { pkg ->
            runCatching {
                val d = ctx.packageManager.getApplicationIcon(pkg)
                val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(b)
                d.setBounds(0, 0, 128, 128); d.draw(c); b
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
