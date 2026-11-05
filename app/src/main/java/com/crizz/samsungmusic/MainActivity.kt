package com.crizz.samsungmusic

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.content.res.ColorStateList
import android.widget.*

class MainActivity : Activity() {
    data class Song(val id: Long, val title: String, val artist: String)
    private val songs = mutableListOf<Song>()
    private var player: MediaPlayer? = null
    private var selected = -1
    private lateinit var list: LinearLayout
    private lateinit var now: TextView
    private lateinit var toggle: Button
    private lateinit var seek: SeekBar
    private val handler = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size.toFloat(); setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun glass() = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.argb(76, 179, 143, 255), Color.argb(34, 255, 255, 255))).apply {
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), Color.argb(75, 255, 255, 255))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(25, 18, 46)
        window.navigationBarColor = Color.rgb(17, 15, 33)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(30, 26, 48), Color.rgb(10, 12, 20), Color.rgb(20, 17, 33)))
        }
        setContentView(root)
        root.addView(text("G L A S S   /   M U S I C", 13, true))
        root.addView(text("Feel every beat.", 32, true).apply { setPadding(0, dp(20), 0, dp(18)) })
        val art = AuroraView()
        root.addView(art, LinearLayout.LayoutParams(-1, dp(160)).apply { bottomMargin = dp(20) })
        root.addView(text("ON YOUR DEVICE", 12, true).apply { alpha = .65f })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, 0) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            background = glass()
        }
        now = text("Choose a song", 16, true)
        card.addView(now)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val previous = Button(this).apply { text = "◀◀"; setOnClickListener { change(-1) } }
        toggle = Button(this).apply { text = "▶"; setOnClickListener { pauseResume() } }
        val next = Button(this).apply { text = "▶▶"; setOnClickListener { change(1) } }
        controls.addView(previous); controls.addView(toggle); controls.addView(next)
        card.addView(controls)
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) { player?.seekTo(progress) }
            })
        }
        card.addView(seek)
        root.addView(card)
        list.layoutTransition = android.animation.LayoutTransition()
        root.alpha = 0f
        root.translationY = dp(14).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(650).setInterpolator(DecelerateInterpolator()).start()
        listOf(previous, toggle, next).forEach { button ->
            button.setTextColor(Color.WHITE)
            button.backgroundTintList = ColorStateList.valueOf(Color.rgb(62, 48, 91))
            button.contentDescription = when (button) { previous -> "Previous song"; next -> "Next song"; else -> "Play or pause" }
        }
        seek.progressTintList = ColorStateList.valueOf(Color.rgb(195, 171, 255))
        seek.thumbTintList = ColorStateList.valueOf(Color.rgb(220, 204, 255))
        handler.post(object : Runnable {
            override fun run() {
                player?.let { try { seek.max = it.duration; seek.progress = it.currentPosition } catch (_: Exception) {} }
                handler.postDelayed(this, 500)
            }
        })
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) loadSongs()
        else requestPermissions(arrayOf(permission), 42)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 42 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadSongs()
        else list.addView(text("Allow music access to show your local songs.", 16))
    }
    private fun loadSongs() {
        songs.clear(); list.removeAllViews()
        contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
            while (cursor.moveToNext()) songs.add(Song(cursor.getLong(0), cursor.getString(1) ?: "Untitled", cursor.getString(2) ?: "Unknown artist"))
        }
        if (songs.isEmpty()) list.addView(text("No songs found. Add MP3 files to your phone's Music folder.", 16))
        songs.forEachIndexed { index, song ->
            val item = text("♫    ${song.title}\n       ${song.artist}", 16).apply {
                setPadding(dp(14), dp(16), dp(14), dp(16))
                background = glass()
                setOnClickListener {
                    animate().scaleX(.97f).scaleY(.97f).setDuration(90).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(220).start()
                    }.start()
                    play(index)
                }
            }
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
    }
    private fun play(index: Int) {
        if (index !in songs.indices) return
        selected = index
        player?.release()
        player = null
        try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songs[index].id)
            player = MediaPlayer.create(this, uri)
            if (player == null) { now.text = "Cannot play this file"; return }
            player?.setOnCompletionListener { change(1) }
            player?.start()
            now.text = songs[index].title + "\n" + songs[index].artist
            now.alpha = .3f
            now.animate().alpha(1f).setDuration(350).start()
            toggle.text = "Ⅱ"
        } catch (e: Exception) { now.text = "Playback error: ${e.message}" }
    }
    private fun change(delta: Int) { if (songs.isNotEmpty()) play(if (selected < 0) 0 else (selected + delta + songs.size) % songs.size) }
    private fun pauseResume() {
        val p = player ?: return
        if (p.isPlaying) { p.pause(); toggle.text = "▶" }
        else { p.start(); toggle.text = "Ⅱ" }
    }
    private inner class AuroraView : View(this@MainActivity) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private val motion = ValueAnimator.ofFloat(0f, 6.283185f).apply {
            duration = 14000
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); motion.start() }
        override fun onDetachedFromWindow() { motion.cancel(); super.onDetachedFromWindow() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            ink.shader = RadialGradient(w * .5f, h * .6f, w * .6f,
                intArrayOf(Color.rgb(88, 54, 132), Color.rgb(18, 19, 31)), null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(0f, 0f, w, h, dp(24).toFloat(), dp(24).toFloat(), ink)
            ink.shader = null
            val cx = w * .5f; val cy = h * .5f
            for (i in 0..5) {
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = dp(1).toFloat()
                ink.color = Color.argb(100 - i * 12, 208, 186, 255)
                val radius = dp(27 + i * 10).toFloat()
                canvas.drawCircle(cx + kotlin.math.sin(phase + i * .3f) * dp(10), cy, radius, ink)
            }
            ink.style = Paint.Style.FILL
            ink.color = Color.WHITE
            ink.textSize = dp(34).toFloat()
            ink.textAlign = Paint.Align.CENTER
            canvas.drawText("♫", cx, cy + dp(12), ink)
        }
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        super.onDestroy()
    }
}
