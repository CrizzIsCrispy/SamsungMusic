package com.crizz.samsungmusic

import android.Manifest
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.view.accessibility.AccessibilityNodeInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.provider.OpenableColumns
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity : Activity() {
    data class Song(val id: Long, val title: String, val artist: String, val album: String, val duration: Long, val source: String = "")
    private val songs = mutableListOf<Song>()
    private var shown = listOf<Song>()
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val covers = object : LruCache<Long, Bitmap>(16 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }
    private val loading = mutableSetOf<Long>()
    private val prefs by lazy { getSharedPreferences("appearance", MODE_PRIVATE) }
    private var theme = 1
    private var light = false
    private var motion = true
    private var glassAmount = 55
    private var page = "Tracks"
    private var query = ""
    private var selected: Song? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var shuffle = false
    private var repeat = false
    private var expanded = false
    private var scanMessage = ""
    private var scanRunning = false
    private var active = false
    private lateinit var playerHost: FrameLayout
    private lateinit var emptyMessage: TextView
    private var coverTint = Color.rgb(109,97,227)
    private var blendSecondary = Color.rgb(88,68,158)
    private var adaptive = true
    private var economy = false
    private var playbackSpeed = 1f
    private var tintSong: Long? = null
    private var tintMotion: ValueAnimator? = null
    private var sleepAt = 0L
    private var lastScan = 0L
    private val upNext = java.util.ArrayDeque<Song>()
    private val artistCounts = mutableMapOf<String,Int>()
    private var sortByArtist = false
    private lateinit var homeWave: WaveSeek
    private lateinit var fullWave: WaveSeek
    private lateinit var homeElapsed: TextView
    private lateinit var homeDuration: TextView
    private lateinit var cardSurface: ArtworkCard
    private lateinit var miniShuffle: TextView
    private lateinit var miniRepeat: TextView
    private var waveEnabled = true
    private val noArtwork = mutableSetOf<Long>()
    private var fallbackArt: Bitmap? = null
    private var fallbackTheme = -1
    private var position = 0
    private val favourites = mutableSetOf<String>()
    private lateinit var root: FrameLayout
    private lateinit var backdrop: Atmosphere
    private lateinit var library: ListView
    private lateinit var adapter: BaseAdapter
    private lateinit var tabs: LinearLayout
    private lateinit var mini: LinearLayout
    private lateinit var miniArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: TextView
    private lateinit var sheet: LinearLayout
    private lateinit var bigArt: ImageView
    private lateinit var bigTitle: TextView
    private lateinit var bigArtist: TextView
    private lateinit var bigPlay: TextView
    private lateinit var love: TextView
    private lateinit var random: TextView
    private lateinit var looping: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var remaining: TextView
    private lateinit var count: TextView
    private var scrubbing = false
    private val palettes = arrayOf(
        intArrayOf(Color.rgb(250, 77, 112), Color.rgb(122, 82, 178)),
        intArrayOf(Color.rgb(119, 143, 255), Color.rgb(194, 95, 186)),
        intArrayOf(Color.rgb(41, 193, 184), Color.rgb(53, 101, 190)),
        intArrayOf(Color.rgb(239, 159, 77), Color.rgb(198, 82, 130))
    )
    private val accent get() = palettes[theme][0]
    private val inkColor get() = if (light) Color.rgb(29, 27, 34) else Color.rgb(249, 247, 251)
    private val secondary get() = if (light) Color.rgb(111, 104, 118) else Color.rgb(165, 159, 176)
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun label(value: String, size: Int = 16, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(inkColor)
        typeface = Typeface.create("sans-serif" + if (bold) "-medium" else "", Typeface.NORMAL)
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun panel(radius: Int = 24, solid: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (light) intArrayOf(Color.argb(240,255,255,255), Color.argb(220,244,240,248))
        else intArrayOf(Color.argb(if(solid) 255 else 155 + glassAmount,37,33,46), Color.argb(if(solid)255 else 130 + glassAmount,23,23,32))
    ).apply { cornerRadius = dp(radius).toFloat(); setStroke(dp(1), if(light)0x35ffffff else 0x24ffffff) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun space(n: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(n)) }
    private fun button(symbol: String, description: String, size: Int = 25, action: () -> Unit) = Control().apply {
        text = symbol; textSize=size.toFloat(); setTextColor(inkColor)
        gravity = Gravity.CENTER; contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        setOnClickListener { pulse(this); action() }
    }
    private fun pulse(view: View) {
        if (!motion) return
        view.animate().cancel()
        view.scaleX = .88f; view.scaleY = .88f
        view.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
    }
    private fun art(size: Int, radius: Int = 12) = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = panel(radius); clipToOutline = true
        contentDescription = "Album artwork"
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        theme = prefs.getInt("theme",1); light = prefs.getBoolean("light",false)
        adaptive=prefs.getBoolean("adaptive",true);economy=prefs.getBoolean("economy",false)
        waveEnabled=prefs.getBoolean("wave",true);sortByArtist=prefs.getBoolean("artistSort",false)
        motion = prefs.getBoolean("motion",true); glassAmount = prefs.getInt("glass",55)
        favourites.addAll(prefs.getStringSet("favourites", emptySet()) ?: emptySet())
        buildUI()
        val permission = if(Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if(checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED) loadSongs()
        else requestPermissions(arrayOf(permission),42)
        ui.post(tick)
    }
    private fun buildUI() {
        window.statusBarColor = if(light) Color.rgb(247,244,250) else Color.rgb(13,12,18)
        window.navigationBarColor = if(light) Color.rgb(247,244,250) else Color.rgb(13,12,18)
        window.decorView.systemUiVisibility = if(light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        root = FrameLayout(this)
        backdrop = Atmosphere()
        root.addView(backdrop, FrameLayout.LayoutParams(-1,-1))
        val body = column().apply { setPadding(dp(22),dp(14),dp(22),dp(8)) }
        root.addView(body, FrameLayout.LayoutParams(-1,-1)); setContentView(root)
        val top = row()
        top.addView(label("Samsung Music",28,true),LinearLayout.LayoutParams(0,dp(68),1f))
        top.addView(button("⚙","Settings",25) { settingsMenu() }.apply { setTextColor(accent) })
        body.addView(top)
        body.addView(label("A new rhythm. Designed by you.",14).apply { setTextColor(secondary) })
        body.addView(space(18))
        val search = EditText(this).apply {
            hint = "Search songs, artists, albums"; textSize = 15f; isSingleLine = true
            setTextColor(inkColor); setHintTextColor(secondary)
            background = panel(16); setPadding(dp(16),0,dp(16),0)
            setText(query); contentDescription = "Search your music library"
            addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s: CharSequence?,start:Int,before:Int,count:Int) { query=s.toString(); filter() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        body.addView(search,LinearLayout.LayoutParams(-1,dp(48)))
        body.addView(space(18))
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false }
        tabs = row(); tabScroll.addView(tabs); body.addView(tabScroll)
        drawTabs()
        val access = row()
        access.addView(label("＋ Add audio",12,true).apply { setTextColor(accent); setOnClickListener{pickAudio()} },LinearLayout.LayoutParams(0,dp(36),1f))
        access.addView(label("↻ Rescan / Access",12,true).apply { setTextColor(secondary);setOnClickListener{accessOptions()} })
        body.addView(access)
        val tools = row().apply { setPadding(0,dp(12),0,dp(8)) }
        count = label("Loading your library…",12).apply { setTextColor(secondary) }
        tools.addView(count,LinearLayout.LayoutParams(0,dp(36),1f))
        tools.addView(button("⤨","Shuffle all",25) { if(songs.isNotEmpty()) { shuffle=true; play(songs.random()) } }.apply { setTextColor(accent) })
        body.addView(tools)
        library = ListView(this).apply {
            divider = null; isVerticalScrollBarEnabled=false; cacheColorHint=Color.TRANSPARENT
            setPadding(0,0,0,dp(10)); clipToPadding=false
        }
        adapter = object: BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p:Int) = shown[p]
            override fun getItemId(p:Int) = shown[p].id
            override fun getView(p:Int, recycled:View?, parent:ViewGroup?):View {
                val song=shown[p]
                val item: LinearLayout
                val holder: SongRow
                if(recycled is LinearLayout && recycled.tag is SongRow){item=recycled;holder=item.tag as SongRow}
                else{
                    item=row().apply { setPadding(0,dp(8),0,dp(8)); minimumHeight=dp(76) }
                    val cover=art(52,12);item.addView(cover)
                    val info=column().apply{setPadding(dp(13),0,dp(8),0)}
                    val name=label("",15,true);val subtitle=label("",12).apply{setTextColor(secondary)}
                    info.addView(name);info.addView(subtitle);item.addView(info,LinearLayout.LayoutParams(0,-2,1f))
                    val menu=button("⋯","Song options"){}
                    item.addView(menu);holder=SongRow(cover,name,subtitle,menu);item.tag=holder
                }
                bindArt(holder.cover,song)
                val title=if(page=="Albums")song.album else if(page=="Artists")song.artist else song.title
                holder.name.text=title;holder.name.setTextColor(if(selected?.id==song.id)accent else inkColor)
                holder.subtitle.text=if(page=="Albums")song.artist else if(page=="Artists")"${artistCounts[song.artist]?:0} songs" else song.artist
                holder.menu.setOnClickListener{songOptions(song)}
                item.setOnClickListener {
                    if(page=="Albums" || page=="Artists") {
                        val group=songs.filter { if(page=="Albums")it.album==song.album else it.artist==song.artist }
                        AlertDialog.Builder(this@MainActivity).setTitle(title).setItems(group.map{it.title}.toTypedArray()) { _,i -> play(group[i]) }.show()
                    } else play(song)
                }
                return item
            }
        }
        library.adapter=adapter
        val empty=label("Scanning your audio…",15).apply { gravity=Gravity.CENTER; maxLines=5; setTextColor(secondary); setOnClickListener{accessOptions()} }
        emptyMessage=empty
        val listFrame=FrameLayout(this)
        listFrame.addView(library,FrameLayout.LayoutParams(-1,-1)); listFrame.addView(empty,FrameLayout.LayoutParams(-1,-1))
        library.emptyView=empty
        body.addView(listFrame,LinearLayout.LayoutParams(-1,0,1f))
        mini=column().apply { setPadding(dp(18),dp(10),dp(18),dp(10)); elevation=dp(5).toFloat() }
        cardSurface=ArtworkCard();mini.background=cardSurface
        val cardTop=row()
        cardTop.addView(label("This phone",11,true).apply{setTextColor(Color.WHITE)},LinearLayout.LayoutParams(0,dp(26),1f))
        cardTop.addView(button("≡","View queue",22){showQueue()}.apply{setTextColor(Color.WHITE)},LinearLayout.LayoutParams(dp(38),dp(26)))
        mini.addView(cardTop)
        val songLine=row()
        miniArt=art(34,9);songLine.addView(miniArt)
        val miniInfo=column().apply { setPadding(dp(12),0,0,0) }
        miniTitle=label("Choose a song",16,true).apply{setTextColor(Color.WHITE)}
        miniArtist=label("On your device",11).apply{setTextColor(0xffddd9e7.toInt())}
        miniInfo.addView(miniTitle);miniInfo.addView(miniArtist)
        songLine.addView(miniInfo,LinearLayout.LayoutParams(0,dp(42),1f))
        songLine.setOnClickListener{showPlayer()};mini.addView(songLine)
        homeWave=WaveSeek();mini.addView(homeWave,LinearLayout.LayoutParams(-1,dp(34)))
        val homeTimes=row()
        homeElapsed=label("0:00",10).apply{setTextColor(0xffddd9e7.toInt())};homeDuration=label("0:00",10).apply{setTextColor(0xffddd9e7.toInt())}
        homeTimes.addView(homeElapsed,LinearLayout.LayoutParams(0,dp(18),1f));homeTimes.addView(homeDuration);mini.addView(homeTimes)
        val homeControls=row().apply{gravity=Gravity.CENTER}
        miniShuffle=button("⤨","Toggle shuffle",22){shuffle=!shuffle;updatePlayer()}
        miniRepeat=button("↻","Toggle repeat",23){repeat=!repeat;updatePlayer()}
        miniPlay=button("▶","Play or pause",25){toggle()}
        listOf(miniShuffle,button("│◂","Previous song",23){next(-1)},miniPlay,button("▸│","Next song",23){next(1)},miniRepeat).forEach{control->
            control.setTextColor(Color.WHITE);homeControls.addView(control,LinearLayout.LayoutParams(0,dp(46),1f))
        };mini.addView(homeControls)
        mini.visibility=if(selected==null)View.GONE else View.VISIBLE
        body.addView(mini,6,LinearLayout.LayoutParams(-1,dp(202)).apply{topMargin=dp(12);bottomMargin=dp(6)})
        body.addView(space(10))
        val navigation=row().apply { gravity=Gravity.CENTER; background=panel(28) }
        listOf("Library","Favourites","Settings").forEachIndexed { i,name ->
            navigation.addView(label(listOf("♫  ","♡  ","◉  ")[i]+name,13,true).apply {
                gravity=Gravity.CENTER; setTextColor(if(i==0)accent else secondary)
                setOnClickListener { pulse(this); when(i) { 0 -> {page="Tracks";drawTabs();filter()};1 -> {page="Favourites";drawTabs();filter()};else -> settingsMenu() } }
            },LinearLayout.LayoutParams(0,dp(48),1f))
        }; body.addView(navigation)
        buildPlayer(); filter(); updatePlayer()
        if(motion) { body.alpha=0f; body.translationY=dp(12).toFloat(); body.animate().alpha(1f).translationY(0f).setDuration(450).start() }
    }
    private data class SongRow(val cover:ImageView,val name:TextView,val subtitle:TextView,val menu:TextView)
    private fun drawTabs() {
        tabs.removeAllViews()
        listOf("Tracks","Albums","Artists","Favourites").forEach { name ->
            tabs.addView(label(name,16,true).apply {
                setPadding(dp(14),0,dp(14),0); setTextColor(if(page==name)inkColor else secondary)
                if(page==name) background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(accent, palettes[theme][1])).apply{cornerRadius=dp(22).toFloat();setStroke(dp(1),0x66ffffff)}
                setOnClickListener { page=name;drawTabs();filter(); if(motion){library.alpha=0f;library.animate().alpha(1f).setDuration(230).start()} }
            },LinearLayout.LayoutParams(-2,dp(40)))
        }
    }
    private fun filter() {
        if(!::adapter.isInitialized) return
        var result=songs.filter { (it.title+it.artist+it.album).contains(query,true) };if(sortByArtist)result=result.sortedWith(compareBy<Song>{it.artist.lowercase()}.thenBy{it.title.lowercase()})
        result=when(page){ "Albums"->result.distinctBy{it.album};"Artists"->result.distinctBy{it.artist};"Favourites"->result.filter{it.id.toString() in favourites};else->result }
        shown=result
        count.text="${result.size} ${when(page){"Albums"->"albums";"Artists"->"artists";else->"songs"}}"
        emptyMessage.text=when {
            scanRunning -> "Scanning audio on your phone…"
            query.isNotEmpty() -> "No matches. Try another search."
            page=="Favourites" -> "Tap a song’s menu to add favourites."
            scanMessage.isNotEmpty() -> scanMessage
            else -> "No indexed audio found.\nTap + Add audio to choose songs from Files."
        }
        adapter.notifyDataSetChanged()
    }
    private fun buildPlayer() {
        sheet=column().apply { setPadding(dp(28),dp(12),dp(28),dp(18)); background=ColorDrawableCompat(); isClickable=true }
        val close=row(); close.addView(button("⌄","Close player",30){hidePlayer()})
        close.addView(label("NOW PLAYING",11,true).apply { gravity=Gravity.CENTER;letterSpacing=.16f },LinearLayout.LayoutParams(0,dp(48),1f))
        close.addView(button("⋯","Player options"){selected?.let{songOptions(it)}?:accessOptions()}); sheet.addView(close)
        sheet.addView(space(16))
        bigArt=art(280,22).apply { elevation=dp(16).toFloat() }
        val artArea=FrameLayout(this)
        artArea.addView(bigArt,FrameLayout.LayoutParams(dp(280),dp(280),Gravity.CENTER))
        artArea.addOnLayoutChangeListener{_,l,t,r,b,_,_,_,_->
            val side=min(r-l,b-t)-dp(12)
            if(side>0 && bigArt.layoutParams.width!=side)bigArt.layoutParams=FrameLayout.LayoutParams(side,side,Gravity.CENTER)
        }
        sheet.addView(artArea,LinearLayout.LayoutParams(-1,0,1f).apply{bottomMargin=dp(16)})
        val titleRow=row(); val info=column()
        bigTitle=label("Your next favourite",25,true);bigArtist=label("Choose a song from your library",17).apply{setTextColor(secondary)}
        info.addView(bigTitle);info.addView(bigArtist)
        titleRow.addView(info,LinearLayout.LayoutParams(0,-2,1f));love=button("♡","Toggle favourite",30){selected?.let{favourite(it)}}
        titleRow.addView(love);sheet.addView(titleRow);sheet.addView(space(18))
        seek=SeekBar(this).apply {
            progressTintList=ColorStateList.valueOf(accent);thumbTintList=ColorStateList.valueOf(inkColor)
            setPadding(0,0,0,0)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(b:SeekBar?) {scrubbing=true}
                override fun onProgressChanged(b:SeekBar?,v:Int,user:Boolean){if(user)elapsed.text=time(v)}
                override fun onStopTrackingTouch(b:SeekBar?) {if(prepared)player?.seekTo(progress);scrubbing=false}
            })
        }
        fullWave=WaveSeek();sheet.addView(fullWave,LinearLayout.LayoutParams(-1,dp(42)))
        val times=row();elapsed=label("0:00",11).apply{setTextColor(secondary)};remaining=label("−0:00",11).apply{setTextColor(secondary);gravity=Gravity.RIGHT}
        times.addView(elapsed,LinearLayout.LayoutParams(0,dp(24),1f));times.addView(remaining);sheet.addView(times)
        val controls=row().apply{gravity=Gravity.CENTER}
        random=button("⤨","Toggle shuffle",25){shuffle=!shuffle;updatePlayer()};controls.addView(random)
        controls.addView(button("│◂","Previous song",29){next(-1)})
        bigPlay=button("▶","Play or pause",42){toggle()};controls.addView(bigPlay,LinearLayout.LayoutParams(0,dp(88),1f))
        controls.addView(button("▸│","Next song",29){next(1)})
        looping=button("↻","Toggle repeat",28){repeat=!repeat;updatePlayer()};controls.addView(looping);sheet.addView(controls)
        sheet.addView(label("QUEUE   ·   PLAYBACK OPTIONS",10,true).apply{setOnClickListener{playbackOptions()}}.apply{setTextColor(secondary);gravity=Gravity.CENTER;letterSpacing=.12f},LinearLayout.LayoutParams(-1,dp(30)))
        playerHost=FrameLayout(this).apply{visibility=View.GONE;isClickable=true}
        playerHost.addView(Atmosphere(true),FrameLayout.LayoutParams(-1,-1))
        playerHost.addView(sheet,FrameLayout.LayoutParams(-1,-1))
        root.addView(playerHost,FrameLayout.LayoutParams(-1,-1))
        var down=0f
        close.setOnTouchListener { _,event -> when(event.actionMasked){MotionEvent.ACTION_DOWN->{down=event.rawY;true};MotionEvent.ACTION_MOVE->{playerHost.translationY=max(0f,event.rawY-down);true};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{if(playerHost.translationY>dp(80))hidePlayer()else playerHost.animate().translationY(0f).setDuration(220).start();true};else->false} }
    }
    private fun showPlayer() {
        if(expanded)return
        if(selected==null){if(songs.isNotEmpty())play(songs.first())else{accessOptions();return}}
        expanded=true;playerHost.visibility=View.VISIBLE;playerHost.translationY=if(motion)root.height.toFloat() else 0f
        playerHost.animate().translationY(0f).setDuration(if(motion)480 else 0).setInterpolator(DecelerateInterpolator(1.8f)).start()
    }
    private fun hidePlayer() {
        expanded=false
        playerHost.animate().translationY(root.height.toFloat()).setDuration(if(motion)350 else 0).withEndAction{playerHost.visibility=View.GONE;backdrop.invalidate()}.start()
    }
    @Deprecated("Legacy back callback")
    override fun onBackPressed(){if(expanded)hidePlayer()else super.onBackPressed()}
    private fun favourite(song:Song){
        val key=song.id.toString();if(!favourites.add(key))favourites.remove(key)
        prefs.edit().putStringSet("favourites",favourites.toSet()).apply();filter();updatePlayer()
    }
    private fun songOptions(song:Song){AlertDialog.Builder(this).setTitle(song.title).setItems(arrayOf("Play now",if(song.id.toString() in favourites)"Remove favourite" else "Add to favourites","Show player","Play next","Track details")){_,i->when(i){0->play(song);1->favourite(song);2->showPlayer();3->{upNext.addFirst(song);Toast.makeText(this,"Added to play next",Toast.LENGTH_SHORT).show()};4->trackDetails(song)}}.show()}
    private fun customise(){
        val box=column().apply{setPadding(dp(24),dp(8),dp(24),dp(12))}
        box.addView(label("Make it yours",25,true));box.addView(space(16));box.addView(label("Colour atmosphere",14))
        val colours=row()
        listOf("Rose","Iris","Ocean","Sunset").forEachIndexed{i,name->colours.addView(label(name,13,true).apply{
            gravity=Gravity.CENTER;setTextColor(palettes[i][0]);setOnClickListener{theme=i;saveAppearance();rebuild();dialog?.dismiss();customise()}
        },LinearLayout.LayoutParams(0,dp(52),1f))};box.addView(colours)
        val day=Switch(this).apply{text="Light appearance";setTextColor(inkColor);isChecked=light;setOnCheckedChangeListener{_,v->light=v;saveAppearance();dialog?.dismiss();rebuild();customise()}}
        box.addView(day)
        box.addView(Switch(this).apply{text="Adaptive album colours";setTextColor(inkColor);isChecked=adaptive;setOnCheckedChangeListener{_,v->adaptive=v;tintSong=null;saveAppearance();updatePlayer()}})
        box.addView(Switch(this).apply{text="Animated progress wave";setTextColor(inkColor);isChecked=waveEnabled;setOnCheckedChangeListener{_,v->waveEnabled=v;saveAppearance();wakeVisuals()}})
        box.addView(Switch(this).apply{text="Battery saver · 30 fps effects";setTextColor(inkColor);isChecked=economy;setOnCheckedChangeListener{_,v->economy=v;saveAppearance();wakeVisuals()}})
        box.addView(Switch(this).apply{text="Fluid animations";setTextColor(inkColor);isChecked=motion;setOnCheckedChangeListener{_,v->motion=v;saveAppearance();backdrop.invalidate();playerHost.getChildAt(0).invalidate()}})
        box.addView(space(12));box.addView(label("Glass tint",14))
        box.addView(SeekBar(this).apply{max=100;progress=glassAmount;progressTintList=ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onStartTrackingTouch(b:SeekBar?){}
                override fun onProgressChanged(b:SeekBar?,v:Int,user:Boolean){glassAmount=v}
                override fun onStopTrackingTouch(b:SeekBar?){saveAppearance();cardSurface.invalidateSelf()}
            })})
        box.addView(label("Themes and favourites are saved on your phone.",12).apply{maxLines=2;setTextColor(secondary)})
        val scrollBox=ScrollView(this).apply{addView(box)}
        dialog=AlertDialog.Builder(this).setView(scrollBox).setPositiveButton("Done"){_,_->rebuild()}.create()
        dialog?.show();dialog?.window?.setBackgroundDrawable(panel(28,true))
    }
    private var dialog:AlertDialog?=null
    private fun saveAppearance(){prefs.edit().putInt("theme",theme).putBoolean("light",light).putBoolean("motion",motion).putInt("glass",glassAmount).putBoolean("adaptive",adaptive).putBoolean("economy",economy).putBoolean("wave",waveEnabled).putBoolean("artistSort",sortByArtist).apply()}
    private fun rebuild(){expanded=false;tintSong=null;buildUI()}
    private fun audioPermission()=if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun songUri(song:Song)=if(song.source.isNotEmpty())Uri.parse(song.source)else ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,song.id)
    private fun accessOptions(){
        AlertDialog.Builder(this).setTitle("Find your music").setMessage("Scan audio indexed by Android, or choose files directly. Audio in private app folders or cloud storage may need to be selected from Files.")
            .setPositiveButton("Choose audio"){_,_->pickAudio()}
            .setNeutralButton("Rescan"){_,_->if(checkSelfPermission(audioPermission())==PackageManager.PERMISSION_GRANTED)loadSongs()else requestPermissions(arrayOf(audioPermission()),42)}
            .setNegativeButton("App settings"){_,_->startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}.show()
    }
    private fun pickAudio(){
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="audio/*";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},73)
    }
    @Deprecated("Legacy activity result")
    override fun onActivityResult(code:Int,result:Int,data:Intent?){
        super.onActivityResult(code,result,data)
        if(code!=73||result!=RESULT_OK||data==null)return
        val saved=(prefs.getStringSet("imports",emptySet())?:emptySet()).toMutableSet()
        val uris=mutableListOf<Uri>();data.clipData?.let{clips->for(i in 0 until clips.itemCount)uris.add(clips.getItemAt(i).uri)}?:data.data?.let{uris.add(it)}
        uris.forEach{uri->try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){};saved.add(uri.toString())}
        prefs.edit().putStringSet("imports",saved).apply();page="Tracks";query="";drawTabs();loadSongs()
    }
    private fun loadSongs(){
        if(scanRunning)return
        scanRunning=true;lastScan=SystemClock.elapsedRealtime();scanMessage="";filter()
        worker.execute{
            val found=mutableListOf<Song>();var error=""
            val permitted=checkSelfPermission(audioPermission())==PackageManager.PERMISSION_GRANTED
            if(permitted)try{
                val collections=if(Build.VERSION.SDK_INT>=29)MediaStore.getExternalVolumeNames(this).map{MediaStore.Audio.Media.getContentUri(it)}else listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                collections.forEach{collection->
                    contentResolver.query(collection,arrayOf("_id","title","artist","album","duration"),null,null,"title COLLATE NOCASE ASC")?.use{c->while(c.moveToNext()){
                        val uri=ContentUris.withAppendedId(collection,c.getLong(0)).toString()
                        found.add(Song(uri.hashCode().toLong(),c.getString(1)?:"Untitled",c.getString(2)?:"Unknown artist",c.getString(3)?:"Unknown album",c.getLong(4),uri))
                    }}
                }
            }catch(e:SecurityException){error="Music access is off. Tap Rescan / Access to enable it."}catch(_:Exception){error="Android could not scan audio. Try Rescan or + Add audio."}
            else error="Allow Music and audio access, or use + Add audio to select files."
            (prefs.getStringSet("imports",emptySet())?:emptySet()).forEach{source->
                if(found.none{it.source==source})try{
                    val uri=Uri.parse(source);var title="Audio file"
                    contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())title=c.getString(0)?:title}
                    found.add(Song(source.hashCode().toLong(),title,"Selected from Files","Imported audio",0,source))
                }catch(_:Exception){}
            }
            ui.post{if(!isDestroyed){scanRunning=false;scanMessage=error;songs.clear();songs.addAll(found.distinctBy{it.source}.sortedBy{it.title.lowercase()});artistCounts.clear();songs.groupingBy{it.artist}.eachCount().forEach{(artist,n)->artistCounts[artist]=n};filter()}}
        }
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==42)loadSongs()}
    override fun onResume(){super.onResume();active=true;if(::adapter.isInitialized&&(songs.isEmpty()||SystemClock.elapsedRealtime()-lastScan>60000))loadSongs();if(::backdrop.isInitialized)backdrop.invalidate();if(::playerHost.isInitialized)playerHost.getChildAt(0).invalidate();wakeVisuals()}
    override fun onPause(){active=false;super.onPause()}
    private fun play(song:Song){
        player?.release();prepared=false;selected=song;position=0
        val fresh=MediaPlayer();player=fresh
        try{
            fresh.setDataSource(this,songUri(song))
            fresh.setOnPreparedListener{if(player===it){prepared=true;it.start();applySpeed();updatePlayer()}}
            fresh.setOnCompletionListener{if(repeat){it.seekTo(0);it.start()}else next(1)}
            fresh.setOnErrorListener{failed,_,_->if(player!==failed)return@setOnErrorListener true;prepared=false;Toast.makeText(this,"This audio file could not be played",Toast.LENGTH_SHORT).show();updatePlayer();true}
            fresh.prepareAsync()
        }catch(_:Exception){fresh.release();player=null;Toast.makeText(this,"Unable to open this song",Toast.LENGTH_SHORT).show()}
        updatePlayer();adapter.notifyDataSetChanged()
    }
    private fun toggle(){val p=player;if(p==null){songs.firstOrNull()?.let{play(it)};return};if(!prepared)return;if(p.isPlaying)p.pause()else p.start();updatePlayer()}
    private fun next(delta:Int){if(delta>0&&upNext.isNotEmpty()){play(upNext.removeFirst());return};if(songs.isEmpty())return;val index=songs.indexOfFirst{it.id==selected?.id};play(if(shuffle)songs.random()else songs[(index+delta+songs.size)%songs.size])}
    private fun updatePlayer(){
        val song=selected;val playing=prepared&&player?.isPlaying==true
        miniTitle.text=song?.title?:"Choose your first song";miniArtist.text=song?.artist?:"On your device"
        bigTitle.text=song?.title?:"Your next favourite";bigArtist.text=song?.artist?:"Choose a song from your library"
        miniPlay.text=if(playing)"Ⅱ"else"▶";bigPlay.text=miniPlay.text
        love.text=if(song?.id.toString() in favourites)"♥"else"♡";love.setTextColor(accent)
        random.setTextColor(if(shuffle)accent else secondary);looping.setTextColor(if(repeat)accent else secondary)
        mini.visibility=if(song==null)View.GONE else View.VISIBLE
        miniShuffle.setTextColor(if(shuffle)Color.WHITE else 0xffb6adbf.toInt());miniRepeat.setTextColor(if(repeat)Color.WHITE else 0xffb6adbf.toInt())
        bindArt(miniArt,song);bindArt(bigArt,song)
        cardSurface.artwork=song?.let{covers.get(it.id)};cardSurface.invalidateSelf()
        selected?.let{song->val cached=covers.get(song.id);if(cached!=null)sampleTint(cached)else if(song.id in noArtwork)sampleTint(placeholder())}
        wakeVisuals()
    }
    private fun time(ms:Int):String{val s=max(0,ms)/1000;return "${s/60}:${(s%60).toString().padStart(2,'0')}"}
    private val tick=object:Runnable{override fun run(){
        if(sleepAt>0 && SystemClock.elapsedRealtime()>=sleepAt){sleepAt=0L;if(prepared&&player?.isPlaying==true)player?.pause();updatePlayer();Toast.makeText(this@MainActivity,"Sleep timer finished",Toast.LENGTH_SHORT).show()}
        if(active&&prepared)try{player?.let{position=it.currentPosition;if(!scrubbing){seek.max=it.duration;seek.progress=position;elapsed.text=time(position);remaining.text="−"+time(it.duration-position);homeElapsed.text=time(position);homeDuration.text=time(it.duration);homeWave.progress=position;homeWave.duration=it.duration;fullWave.progress=position;fullWave.duration=it.duration;homeWave.invalidate();fullWave.invalidate()}}}catch(_:IllegalStateException){}
        ui.postDelayed(this,if(active)250 else 1000)
    }}
    private fun placeholder():Bitmap{
        if(fallbackTheme==theme)fallbackArt?.let{return it}
        val b=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);val c=Canvas(b);val p=Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader=LinearGradient(0f,0f,128f,128f,palettes[theme],null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,128f,128f,p)
        p.shader=null;p.color=Color.argb(60,255,255,255);c.drawCircle(96f,18f,65f,p);p.color=Color.WHITE;p.textSize=66f;p.textAlign=Paint.Align.CENTER;c.drawText("♫",64f,88f,p);fallbackTheme=theme;fallbackArt=b;return b
    }
    private fun bindArt(view:ImageView,song:Song?){
        view.tag=song?.id;view.setImageBitmap(song?.let{covers.get(it.id)}?:placeholder())
        if(song==null||covers.get(song.id)!=null||song.id in noArtwork||!loading.add(song.id))return
        worker.execute{
            var bitmap:Bitmap?=null
            val retriever=MediaMetadataRetriever()
            try{retriever.setDataSource(this,songUri(song));retriever.embeddedPicture?.let{bytes->
                val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                options.inSampleSize=max(1,max(options.outWidth,options.outHeight)/700);options.inJustDecodeBounds=false
                bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            }}catch(_:Exception){}finally{try{retriever.release()}catch(_:Exception){}}
            ui.post{loading.remove(song.id);if(bitmap==null)noArtwork.add(song.id);bitmap?.let{b->covers.put(song.id,b);if(!isDestroyed){if(view.tag==song.id)view.setImageBitmap(b);if(selected?.id==song.id){miniArt.setImageBitmap(b);bigArt.setImageBitmap(b);tintSong=null;sampleTint(b);cardSurface.artwork=b;cardSurface.invalidateSelf()};refreshVisibleArtwork(song.id,b)}}}
        }
    }
    private inner class Atmosphere(private val immersive:Boolean=false):View(this@MainActivity){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var start=SystemClock.uptimeMillis()
        private val path=Path()
        private val ribbonColours=IntArray(3)
        private var lastFrame=0L
        private var gradient:Shader?=null
        private var lastW=0
        private var lastTheme=-1
        private var lastTint=0
        private var lastH=0
        private var ribbonShader:Shader?=null
        private var shaderColour=0
        private var shaderWidth=0
        private var shaderHeight=0
        override fun onDraw(c:Canvas){
            val w=width.toFloat();val h=height.toFloat();val t=if(motion)(SystemClock.uptimeMillis()-start)/8000f else 0f
            c.drawColor(if(light)Color.rgb(241,242,252)else Color.rgb(9,11,24))
            if(lastW!=width||lastTheme!=theme||lastH!=height||lastTint!=coverTint){
                lastW=width;lastTheme=theme;lastH=height;lastTint=coverTint
                gradient=RadialGradient(w*.5f,h*.3f,w,Color.argb(if(light)45 else 95,100,95,210),Color.TRANSPARENT,Shader.TileMode.CLAMP)
            }
                // The palette is rebuilt only when size or colour changes.
            paint.shader=gradient;c.drawRect(0f,0f,w,h,paint)
            ribbonColours[0]=Color.argb(if(immersive)150 else 60,83,141,255)
            ribbonColours[1]=Color.argb(if(immersive)135 else 50,Color.red(coverTint),Color.green(coverTint),Color.blue(coverTint))
            ribbonColours[2]=Color.argb(if(immersive)120 else 40,191,70,244)
            if(ribbonShader==null||shaderColour!=coverTint||shaderWidth!=width||shaderHeight!=height){ribbonShader=LinearGradient(0f,h*.2f,w,h*.8f,ribbonColours,null,Shader.TileMode.CLAMP);shaderColour=coverTint;shaderWidth=width;shaderHeight=height}
            paint.shader=ribbonShader
            val centre=if(immersive)h*.38f else h*.68f
            for(i in 0..4){
                val off=i*0.4f;val sway=sin(t+off)*h*.09f
                path.reset();path.moveTo(-w*.2f,centre+sway+dp(i*7))
                path.cubicTo(w*.15f,centre-h*.3f+cos(t+off)*h*.09f,w*.55f,centre+h*.32f,w*1.2f,centre-h*.12f+sway)
                path.cubicTo(w*.6f,centre+h*.5f+sin(t*.8f)*h*.08f,w*.2f,centre-h*.14f,w*-.2f,centre+sway+dp(i*7))
                path.close();paint.style=Paint.Style.FILL;paint.alpha=if(i==0)120 else 35;c.drawPath(path,paint)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1).toFloat();paint.alpha=if(immersive)140 else 60;c.drawPath(path,paint)
            }
            paint.alpha=255;paint.shader=null;paint.style=Paint.Style.FILL
            if(shouldAnimate()&&(immersive||!expanded)&&isShown&&windowVisibility==View.VISIBLE){lastFrame=SystemClock.uptimeMillis();if(economy)postInvalidateDelayed(33)else postInvalidateOnAnimation()}
        }
    }
    private fun ColorDrawableCompat()=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
    private fun sampleTint(bitmap:Bitmap){
        if(tintSong==selected?.id)return
        tintSong=selected?.id
        var target=palettes[theme][0];var second=palettes[theme][1]
        if(adaptive)try{
            val small=Bitmap.createScaledBitmap(bitmap,12,12,true);val pixels=IntArray(144);small.getPixels(pixels,0,12,0,0,12,12)
            val hsv=FloatArray(3);var weight=0f;var red=0f;var green=0f;var blue=0f;var brightest=0f
            pixels.forEach{col->Color.colorToHSV(col,hsv);val score=hsv[1]*hsv[2];if(score>brightest){brightest=score;second=col};val v=.08f+score;red+=Color.red(col)*v;green+=Color.green(col)*v;blue+=Color.blue(col)*v;weight+=v}
            target=Color.rgb((red/weight).toInt(),(green/weight).toInt(),(blue/weight).toInt());Color.colorToHSV(target,hsv);hsv[1]=max(.35f,hsv[1]);hsv[2]=max(.55f,hsv[2]);target=Color.HSVToColor(hsv)
            small.recycle()
        }catch(_:Exception){}
        blendSecondary=second;tintMotion?.cancel();val from=coverTint
        if(motion&&active)tintMotion=ValueAnimator.ofArgb(from,target).apply{duration=850;addUpdateListener{coverTint=it.animatedValue as Int;cardSurface.invalidateSelf();wakeVisuals()};start()}
        else{coverTint=target;cardSurface.invalidateSelf();wakeVisuals()}
    }
    private inner class Control:TextView(this@MainActivity){
        private val brush=Paint(Paint.ANTI_ALIAS_FLAG)
        private val shape=Path()
        private var controlShader:Shader?=null
        private var cachedTint=0
        private var cachedWidth=0
        private var cachedHeight=0
        override fun onDraw(c:Canvas){
            val mark=text.toString();val transport=mark=="▶"||mark=="Ⅱ"||mark.contains("│")
            if(!transport){super.onDraw(c);return}
            val cx=width*.5f;val cy=height*.5f
            val main=height>=dp(80);val radius=if(main)dp(35).toFloat()else dp(21).toFloat()
            if(controlShader==null||cachedTint!=coverTint||cachedWidth!=width||cachedHeight!=height){cachedTint=coverTint;cachedWidth=width;cachedHeight=height;controlShader=RadialGradient(cx-radius*.35f,cy-radius*.5f,radius*1.8f,intArrayOf(colourMix(coverTint,Color.WHITE,.4f),colourMix(coverTint,Color.BLACK,.25f),colourMix(coverTint,Color.BLACK,.65f)),null,Shader.TileMode.CLAMP)}
            brush.shader=controlShader
            brush.style=Paint.Style.FILL;c.drawCircle(cx,cy,radius,brush)
            brush.shader=null;brush.style=Paint.Style.STROKE;brush.strokeWidth=dp(1).toFloat();brush.color=Color.argb(130,205,208,255);c.drawCircle(cx,cy,radius,brush)
            brush.style=Paint.Style.FILL;brush.color=Color.WHITE
            val unit=if(main)dp(12).toFloat()else dp(7).toFloat()
            if(mark=="Ⅱ"){
                c.drawRoundRect(cx-unit*.7f,cy-unit,cx-unit*.2f,cy+unit,dp(1).toFloat(),dp(1).toFloat(),brush)
                c.drawRoundRect(cx+unit*.2f,cy-unit,cx+unit*.7f,cy+unit,dp(1).toFloat(),dp(1).toFloat(),brush)
            }else{
                val direction=if(mark.startsWith("│"))-1 else 1
                shape.reset();shape.moveTo(cx-unit*.6f*direction,cy-unit);shape.lineTo(cx+unit*.9f*direction,cy);shape.lineTo(cx-unit*.6f*direction,cy+unit);shape.close();c.drawPath(shape,brush)
                if(mark.contains("│")){val x=cx+unit*direction;c.drawRoundRect(x-dp(1),cy-unit,x+dp(1),cy+unit,dp(1).toFloat(),dp(1).toFloat(),brush)}
            }
        }
    }

    private fun settingsMenu(){
        val choices=arrayOf("Appearance & motion","Playback options","Library access & rescan","Sort: ${if(sortByArtist)"Artist" else "Title"}","About")
        AlertDialog.Builder(this).setTitle("Settings").setItems(choices){_,i->when(i){
            0->customise();1->playbackOptions();2->accessOptions();3->{sortByArtist=!sortByArtist;saveAppearance();filter();settingsMenu()};4->about()
        }}.show()
    }
    private fun about(){
        val version=try{packageManager.getPackageInfo(packageName,0).versionName?:"1.3"}catch(_:Exception){"1.3"}
        val content=column().apply{setPadding(dp(26),dp(24),dp(26),dp(20))}
        content.addView(label("Glass Music",28,true))
        content.addView(label("Version $version",13).apply{setTextColor(secondary)})
        content.addView(space(24))
        content.addView(label("Design by Crizz",22,true).apply{setTextColor(accent)})
        content.addView(space(14))
        content.addView(label("An independent music player with adaptive colour, ambient motion and a familiar Galaxy-inspired library.\n\nThe animated wave is a decorative playback effect.\n\nNot affiliated with Samsung or Apple.",14).apply{maxLines=10;setTextColor(secondary)})
        val info=AlertDialog.Builder(this).setView(content).setPositiveButton("Done",null).create()
        info.show();info.window?.setBackgroundDrawable(panel(28,true))
    }
    private fun playbackOptions(){
        val timer=if(sleepAt>0)"${max(1L,(sleepAt-SystemClock.elapsedRealtime())/60000)} min left" else "Off"
        AlertDialog.Builder(this).setTitle("Playback").setItems(arrayOf("Up next · ${upNext.size} queued","Speed · ${playbackSpeed}×","Sleep timer · $timer")){_,i->when(i){
            0->showQueue()
            1->{val rates=floatArrayOf(.75f,1f,1.25f,1.5f,2f);AlertDialog.Builder(this).setTitle("Playback speed").setSingleChoiceItems(rates.map{"${it}×"}.toTypedArray(),rates.indexOf(playbackSpeed)){d,which->playbackSpeed=rates[which];applySpeed();d.dismiss()}.show()}
            2->{val minutes=intArrayOf(0,15,30,45,60);AlertDialog.Builder(this).setTitle("Sleep timer").setItems(arrayOf("Off","15 minutes","30 minutes","45 minutes","60 minutes")){_,which->sleepAt=if(minutes[which]==0)0 else SystemClock.elapsedRealtime()+minutes[which]*60000L;Toast.makeText(this,if(sleepAt==0L)"Sleep timer off" else "Music will pause in ${minutes[which]} minutes",Toast.LENGTH_SHORT).show()}.show()}
        }}.show()
    }
    private fun applySpeed(){
        if(!prepared)return
        try{player?.let{val wasPlaying=it.isPlaying;it.playbackParams=it.playbackParams.setSpeed(playbackSpeed);if(!wasPlaying)it.pause()}}catch(_:Exception){playbackSpeed=1f;Toast.makeText(this,"Speed is unavailable for this file",Toast.LENGTH_SHORT).show()}
    }
    private fun showQueue(){
        val queued=upNext.toList()
        if(queued.isEmpty()){AlertDialog.Builder(this).setTitle("Up next").setMessage("Your library continues automatically. Use a song’s ⋯ menu and choose Play next to queue it.").setPositiveButton("Done",null).show();return}
        AlertDialog.Builder(this).setTitle("Up next").setItems(queued.map{it.title}.toTypedArray()){_,i->kotlin.repeat(i+1){upNext.removeFirst()};play(queued[i])}.setNeutralButton("Clear queue"){_,_->upNext.clear()}.setPositiveButton("Done",null).show()
    }
    private fun trackDetails(song:Song){
        AlertDialog.Builder(this).setTitle(song.title).setMessage("Artist: ${song.artist}\nAlbum: ${song.album}\nDuration: ${if(song.duration>0)time(song.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) else "Available during playback"}").setPositiveButton("Done",null).show()
    }
    private fun shouldAnimate()=motion&&active&&prepared&&player?.isPlaying==true
    private fun wakeVisuals(){
        if(!::root.isInitialized)return
        if(::backdrop.isInitialized)backdrop.invalidate()
        if(::playerHost.isInitialized)playerHost.getChildAt(0).invalidate()
        if(::homeWave.isInitialized)homeWave.invalidate()
        if(::fullWave.isInitialized)fullWave.invalidate()
        if(::miniPlay.isInitialized)miniPlay.invalidate()
        if(::bigPlay.isInitialized)bigPlay.invalidate()
    }
    private fun refreshVisibleArtwork(id:Long,bitmap:Bitmap){
        if(!::library.isInitialized)return
        for(i in 0 until library.childCount){val holder=library.getChildAt(i).tag as? SongRow?:continue;if(holder.cover.tag==id)holder.cover.setImageBitmap(bitmap)}
    }
    private fun colourMix(a:Int,b:Int,f:Float):Int{
        val t=f.coerceIn(0f,1f)
        return Color.rgb((Color.red(a)+(Color.red(b)-Color.red(a))*t).toInt(),(Color.green(a)+(Color.green(b)-Color.green(a))*t).toInt(),(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).toInt())
    }
    private inner class ArtworkCard:Drawable(){
        var artwork:Bitmap?=null
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clip=Path()
        private val box=RectF()
        private val imageBox=RectF()
        private var gradient:Shader?=null
        private var tint=0
        override fun onBoundsChange(bounds:Rect){
            box.set(bounds);clip.reset();clip.addRoundRect(box,dp(28).toFloat(),dp(28).toFloat(),Path.Direction.CW);tint=0
        }
        override fun draw(canvas:Canvas){
            if(box.isEmpty)return
            canvas.save();canvas.clipPath(clip)
            paint.shader=null;paint.color=colourMix(coverTint,Color.BLACK,.65f);canvas.drawRect(box,paint)
            artwork?.let{bitmap->
                val scale=max(box.width()/bitmap.width,box.height()/bitmap.height);val w=bitmap.width*scale;val h=bitmap.height*scale
                imageBox.set(box.centerX()-w/2,box.centerY()-h/2,box.centerX()+w/2,box.centerY()+h/2)
                paint.alpha=115;canvas.drawBitmap(bitmap,null,imageBox,paint);paint.alpha=255
            }
            if(tint!=coverTint||gradient==null){tint=coverTint;gradient=LinearGradient(0f,box.top,box.width(),box.bottom,intArrayOf(Color.argb(125,Color.red(coverTint),Color.green(coverTint),Color.blue(coverTint)),0xd90a0912.toInt()),null,Shader.TileMode.CLAMP)}
            paint.shader=gradient;canvas.drawRect(box,paint);paint.shader=null
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1).toFloat();paint.color=0x40ffffff;canvas.drawRoundRect(box,dp(28).toFloat(),dp(28).toFloat(),paint);paint.style=Paint.Style.FILL
            canvas.restore()
        }
        override fun setAlpha(alpha:Int){}
        override fun setColorFilter(filter:ColorFilter?){}
        @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    private inner class WaveSeek:View(this@MainActivity){
        var progress=0
        var duration=1
        private val brush=Paint(Paint.ANTI_ALIAS_FLAG)
        private val crest=Path()
        private var waveShader:Shader?=null
        private var lastTint=0
        private var lastWidth=0
        private var dragging=false
        private var cursor=0f
        init{isFocusable=true;isClickable=true;contentDescription="Playback position"}
        override fun onDraw(c:Canvas){
            val inset=dp(7).toFloat();val w=(width-inset*2).coerceAtLeast(1f);val centre=height*.68f
            val ratio=if(dragging)cursor else (progress.toFloat()/duration.coerceAtLeast(1)).coerceIn(0f,1f)
            val end=inset+w*ratio
            brush.shader=null;brush.strokeWidth=dp(3).toFloat();brush.strokeCap=Paint.Cap.ROUND;brush.color=0x55ffffff;brush.style=Paint.Style.STROKE
            c.drawLine(inset,centre,width-inset,centre,brush)
            if(lastTint!=coverTint||lastWidth!=width||waveShader==null){lastTint=coverTint;lastWidth=width;waveShader=LinearGradient(0f,0f,width.toFloat(),0f,intArrayOf(colourMix(coverTint,Color.WHITE,.35f),coverTint,colourMix(blendSecondary,Color.WHITE,.2f)),null,Shader.TileMode.CLAMP)}
            brush.shader=waveShader
            if(waveEnabled&&ratio>0f){
                val time=if(motion)SystemClock.uptimeMillis()/520f else 0f
                crest.reset();crest.moveTo(inset,centre)
                val steps=80
                for(i in 0..steps){val f=i.toFloat()/steps;val x=inset+(end-inset)*f;val envelope=sin(f*Math.PI.toFloat()).pow(1.3f);val wave=sin(f*19f-time)*sin(f*8f+time*.6f);crest.lineTo(x,centre-wave*dp(10)*envelope)}
                brush.strokeWidth=dp(2).toFloat();c.drawPath(crest,brush)
                brush.strokeWidth=dp(3).toFloat();c.drawLine(inset,centre,end,centre,brush)
            }else c.drawLine(inset,centre,end,centre,brush)
            brush.shader=null;brush.style=Paint.Style.FILL;brush.color=coverTint;c.drawCircle(end,centre,dp(6).toFloat(),brush)
            brush.style=Paint.Style.STROKE;brush.strokeWidth=dp(2).toFloat();brush.color=Color.WHITE;c.drawCircle(end,centre,dp(6).toFloat(),brush)
            if(waveEnabled&&shouldAnimate()&&isShown&&windowVisibility==View.VISIBLE&&(this!==homeWave||!expanded)){if(economy)postInvalidateDelayed(33)else postInvalidateOnAnimation()}
        }
        override fun onTouchEvent(event:MotionEvent):Boolean{
            if(!prepared)return false
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{dragging=true;scrubbing=true;parent.requestDisallowInterceptTouchEvent(true)}
                MotionEvent.ACTION_MOVE->{}
                MotionEvent.ACTION_UP->{cursor=((event.x-dp(7))/(width-dp(14)).coerceAtLeast(1)).coerceIn(0f,1f);player?.seekTo((duration*cursor).toInt());progress=(duration*cursor).toInt();dragging=false;scrubbing=false;performClick()}
                MotionEvent.ACTION_CANCEL->{dragging=false;scrubbing=false}
            }
            if(dragging){cursor=((event.x-dp(7))/(width-dp(14)).coerceAtLeast(1)).coerceIn(0f,1f);elapsed.text=time((duration*cursor).toInt());homeElapsed.text=elapsed.text}
            invalidate();return true
        }
        override fun performClick():Boolean{super.performClick();return true}
        override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){
            super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.SeekBar";info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0f,duration.coerceAtLeast(1).toFloat(),progress.coerceIn(0,duration.coerceAtLeast(1)).toFloat());info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        }
        override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean{
            if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id&&prepared){val value=args?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?:return false;player?.seekTo(value.toInt().coerceIn(0,duration));return true}
            return super.performAccessibilityAction(action,args)
        }
    }

    override fun onDestroy(){ui.removeCallbacksAndMessages(null);tintMotion?.cancel();player?.release();worker.shutdownNow();super.onDestroy()}
}
