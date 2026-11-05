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
    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.WHITE)
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
                intArrayOf(Color.rgb(54, 30, 90), Color.rgb(18, 18, 37), Color.rgb(27, 20, 48)))
        }
        setContentView(root)
        root.addView(text("SAMSUNG  ◇  GLASS MUSIC", 13, true))
        root.addView(text("Your music,\nreimagined.", 32, true).apply { setPadding(0, dp(20), 0, dp(18)) })
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
                setOnClickListener { play(index) }
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
            now.text = songs[index].title
            toggle.text = "Ⅱ"
        } catch (e: Exception) { now.text = "Playback error: ${e.message}" }
    }
    private fun change(delta: Int) { if (songs.isNotEmpty()) play(if (selected < 0) 0 else (selected + delta + songs.size) % songs.size) }
    private fun pauseResume() {
        val p = player ?: return
        if (p.isPlaying) { p.pause(); toggle.text = "▶" }
        else { p.start(); toggle.text = "Ⅱ" }
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        super.onDestroy()
    }
}
