package com.crizz.samsungmusic

import android.Manifest
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
    private val pendingCovers = mutableSetOf<Long>()
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
        top.addView(button("◉","Customise appearance",27) { customise() }.apply { setTextColor(accent) })
        body.addView(top)
        body.addView(label("Your library, in a new light.",14).apply { setTextColor(secondary) })
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
                val item = row().apply { setPadding(0,dp(8),0,dp(8)); minimumHeight=dp(76) }
                val cover=art(56,10); bindArt(cover,song); item.addView(cover)
                val info=column().apply { setPadding(dp(14),0,dp(8),0) }
                val title = if(page=="Albums") song.album else if(page=="Artists") song.artist else song.title
                info.addView(label(title,16,true).apply { if(selected?.id==song.id) setTextColor(accent) })
                info.addView(label(if(page=="Albums") song.artist else if(page=="Artists") "${songs.count { it.artist==song.artist }} songs" else song.artist,13).apply { setTextColor(secondary) })
                item.addView(info,LinearLayout.LayoutParams(0,-2,1f))
                item.addView(button("⋯","Song options") { songOptions(song) })
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
        mini=row().apply { background=panel(22); setPadding(dp(10),dp(10),dp(7),dp(10)); elevation=dp(8).toFloat() }
        miniArt=art(44,10); mini.addView(miniArt)
        val miniInfo=column().apply { setPadding(dp(12),0,0,0) }
        miniTitle=label("Choose your first song",14,true); miniArtist=label("On your device",12).apply {setTextColor(secondary)}
        miniInfo.addView(miniTitle); miniInfo.addView(miniArtist)
        mini.addView(miniInfo,LinearLayout.LayoutParams(0,-2,1f))
        miniPlay=button("▶","Play or pause",23){toggle()}; mini.addView(miniPlay)
        mini.addView(button("▸│","Next song",23){next(1)})
        mini.setOnClickListener { showPlayer() }; body.addView(mini,LinearLayout.LayoutParams(-1,dp(70)))
        body.addView(space(12))
        val navigation=row().apply { gravity=Gravity.CENTER; background=panel(28) }
        listOf("Library","Favourites","Style").forEachIndexed { i,name ->
            navigation.addView(label(listOf("♫  ","♡  ","◉  ")[i]+name,13,true).apply {
                gravity=Gravity.CENTER; setTextColor(if(i==0)accent else secondary)
                setOnClickListener { pulse(this); when(i) { 0 -> {page="Tracks";drawTabs();filter()};1 -> {page="Favourites";drawTabs();filter()};else -> customise() } }
            },LinearLayout.LayoutParams(0,dp(48),1f))
        }; body.addView(navigation)
        buildPlayer(); filter(); updatePlayer()
        if(motion) { body.alpha=0f; body.translationY=dp(12).toFloat(); body.animate().alpha(1f).translationY(0f).setDuration(450).start() }
    }
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
        var result=songs.filter { (it.title+it.artist+it.album).contains(query,true) }
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
        };sheet.addView(seek,LinearLayout.LayoutParams(-1,dp(30)))
        val times=row();elapsed=label("0:00",11).apply{setTextColor(secondary)};remaining=label("−0:00",11).apply{setTextColor(secondary);gravity=Gravity.RIGHT}
        times.addView(elapsed,LinearLayout.LayoutParams(0,dp(24),1f));times.addView(remaining);sheet.addView(times)
        val controls=row().apply{gravity=Gravity.CENTER}
        random=button("⤨","Toggle shuffle",25){shuffle=!shuffle;updatePlayer()};controls.addView(random)
        controls.addView(button("│◂","Previous song",29){next(-1)})
        bigPlay=button("▶","Play or pause",42){toggle()};controls.addView(bigPlay,LinearLayout.LayoutParams(0,dp(88),1f))
        controls.addView(button("▸│","Next song",29){next(1)})
        looping=button("↻","Toggle repeat",28){repeat=!repeat;updatePlayer()};controls.addView(looping);sheet.addView(controls)
        sheet.addView(label("ON THIS PHONE",10,true).apply{setTextColor(secondary);gravity=Gravity.CENTER;letterSpacing=.12f},LinearLayout.LayoutParams(-1,dp(30)))
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
        playerHost.animate().translationY(root.height.toFloat()).setDuration(if(motion)350 else 0).withEndAction{playerHost.visibility=View.GONE}.start()
    }
    @Deprecated("Legacy back callback")
    override fun onBackPressed(){if(expanded)hidePlayer()else super.onBackPressed()}
    private fun favourite(song:Song){
        val key=song.id.toString();if(!favourites.add(key))favourites.remove(key)
        prefs.edit().putStringSet("favourites",favourites.toSet()).apply();filter();updatePlayer()
    }
    private fun songOptions(song:Song){AlertDialog.Builder(this).setTitle(song.title).setItems(arrayOf("Play now",if(song.id.toString() in favourites)"Remove favourite" else "Add to favourites","Show player")){_,i->when(i){0->play(song);1->favourite(song);2->showPlayer()}}.show()}
    private fun customise(){
        val box=column().apply{setPadding(dp(24),dp(8),dp(24),dp(12))}
        box.addView(label("Make it yours",25,true));box.addView(space(16));box.addView(label("Colour atmosphere",14))
        val colours=row()
        listOf("Rose","Iris","Ocean","Sunset").forEachIndexed{i,name->colours.addView(label(name,13,true).apply{
            gravity=Gravity.CENTER;setTextColor(palettes[i][0]);setOnClickListener{theme=i;saveAppearance();rebuild();dialog?.dismiss();customise()}
        },LinearLayout.LayoutParams(0,dp(52),1f))};box.addView(colours)
        val day=Switch(this).apply{text="Light appearance";setTextColor(inkColor);isChecked=light;setOnCheckedChangeListener{_,v->light=v;saveAppearance();dialog?.dismiss();rebuild();customise()}}
        box.addView(day)
        box.addView(Switch(this).apply{text="Fluid animations";setTextColor(inkColor);isChecked=motion;setOnCheckedChangeListener{_,v->motion=v;saveAppearance();backdrop.invalidate()}})
        box.addView(space(12));box.addView(label("Glass tint",14))
        box.addView(SeekBar(this).apply{max=100;progress=glassAmount;progressTintList=ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onStartTrackingTouch(b:SeekBar?){}
                override fun onProgressChanged(b:SeekBar?,v:Int,user:Boolean){glassAmount=v}
                override fun onStopTrackingTouch(b:SeekBar?){saveAppearance();mini.background=panel(22)}
            })})
        box.addView(label("Themes and favourites are saved on your phone.",12).apply{maxLines=2;setTextColor(secondary)})
        dialog=AlertDialog.Builder(this).setView(box).setPositiveButton("Done"){_,_->rebuild()}.create()
        dialog?.show();dialog?.window?.setBackgroundDrawable(panel(28,true))
    }
    private var dialog:AlertDialog?=null
    private fun saveAppearance(){prefs.edit().putInt("theme",theme).putBoolean("light",light).putBoolean("motion",motion).putInt("glass",glassAmount).apply()}
    private fun rebuild(){expanded=false;buildUI()}
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
        scanRunning=true;scanMessage="";filter()
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
            ui.post{if(!isDestroyed){scanRunning=false;scanMessage=error;songs.clear();songs.addAll(found.distinctBy{it.source}.sortedBy{it.title.lowercase()});filter()}}
        }
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==42)loadSongs()}
    override fun onResume(){super.onResume();active=true;if(::adapter.isInitialized)loadSongs();if(::backdrop.isInitialized)backdrop.invalidate();if(::playerHost.isInitialized)playerHost.invalidate()}
    override fun onPause(){active=false;super.onPause()}
    private fun play(song:Song){
        player?.release();prepared=false;selected=song;position=0
        val fresh=MediaPlayer();player=fresh
        try{
            fresh.setDataSource(this,songUri(song))
            fresh.setOnPreparedListener{if(player===it){prepared=true;it.start();updatePlayer()}}
            fresh.setOnCompletionListener{if(repeat){it.seekTo(0);it.start()}else next(1)}
            fresh.setOnErrorListener{_,_,_->prepared=false;Toast.makeText(this,"This audio file could not be played",Toast.LENGTH_SHORT).show();updatePlayer();true}
            fresh.prepareAsync()
        }catch(_:Exception){fresh.release();player=null;Toast.makeText(this,"Unable to open this song",Toast.LENGTH_SHORT).show()}
        updatePlayer();adapter.notifyDataSetChanged()
    }
    private fun toggle(){val p=player;if(p==null){songs.firstOrNull()?.let{play(it)};return};if(!prepared)return;if(p.isPlaying)p.pause()else p.start();updatePlayer()}
    private fun next(delta:Int){if(songs.isEmpty())return;val index=songs.indexOfFirst{it.id==selected?.id};play(if(shuffle)songs.random()else songs[(index+delta+songs.size)%songs.size])}
    private fun updatePlayer(){
        val song=selected;val playing=prepared&&player?.isPlaying==true
        miniTitle.text=song?.title?:"Choose your first song";miniArtist.text=song?.artist?:"On your device"
        bigTitle.text=song?.title?:"Your next favourite";bigArtist.text=song?.artist?:"Choose a song from your library"
        miniPlay.text=if(playing)"Ⅱ"else"▶";bigPlay.text=miniPlay.text
        love.text=if(song?.id.toString() in favourites)"♥"else"♡";love.setTextColor(accent)
        random.setTextColor(if(shuffle)accent else secondary);looping.setTextColor(if(repeat)accent else secondary)
        bindArt(miniArt,song);bindArt(bigArt,song)
        backdrop.invalidate()
    }
    private fun time(ms:Int):String{val s=max(0,ms)/1000;return "${s/60}:${(s%60).toString().padStart(2,'0')}"}
    private val tick=object:Runnable{override fun run(){
        if(prepared)try{player?.let{position=it.currentPosition;if(!scrubbing){seek.max=it.duration;seek.progress=position;elapsed.text=time(position);remaining.text="−"+time(it.duration-position)}}}catch(_:IllegalStateException){}
        ui.postDelayed(this,250)
    }}
    private fun placeholder():Bitmap{
        val b=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);val c=Canvas(b);val p=Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader=LinearGradient(0f,0f,128f,128f,palettes[theme],null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,128f,128f,p)
        p.shader=null;p.color=Color.argb(60,255,255,255);c.drawCircle(96f,18f,65f,p);p.color=Color.WHITE;p.textSize=66f;p.textAlign=Paint.Align.CENTER;c.drawText("♫",64f,88f,p);return b
    }
    private fun bindArt(view:ImageView,song:Song?){
        view.tag=song?.id;view.setImageBitmap(song?.let{covers.get(it.id)}?:placeholder())
        if(song==null||covers.get(song.id)!=null||!loading.add(song.id))return
        worker.execute{
            var bitmap:Bitmap?=null
            val retriever=MediaMetadataRetriever()
            try{retriever.setDataSource(this,songUri(song));retriever.embeddedPicture?.let{bytes->
                val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                options.inSampleSize=max(1,max(options.outWidth,options.outHeight)/700);options.inJustDecodeBounds=false
                bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            }}catch(_:Exception){}finally{try{retriever.release()}catch(_:Exception){}}
            ui.post{loading.remove(song.id);bitmap?.let{b->covers.put(song.id,b);if(!isDestroyed){if(view.tag==song.id)view.setImageBitmap(b);if(selected?.id==song.id){miniArt.setImageBitmap(b);bigArt.setImageBitmap(b);sampleTint(b)};adapter.notifyDataSetChanged()}}}
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
        override fun onDraw(c:Canvas){
            val w=width.toFloat();val h=height.toFloat();val t=if(motion)(SystemClock.uptimeMillis()-start)/8000f else 0f
            c.drawColor(if(light)Color.rgb(241,242,252)else Color.rgb(9,11,24))
            if(lastW!=width||lastTheme!=theme){
                lastW=width;lastTheme=theme
                gradient=RadialGradient(w*.5f,h*.3f,w,Color.argb(if(light)45 else 95,100,95,210),Color.TRANSPARENT,Shader.TileMode.CLAMP)
            }
            paint.shader=gradient;c.drawRect(0f,0f,w,h,paint)
            ribbonColours[0]=Color.argb(if(immersive)150 else 60,83,141,255)
            ribbonColours[1]=Color.argb(if(immersive)135 else 50,Color.red(coverTint),Color.green(coverTint),Color.blue(coverTint))
            ribbonColours[2]=Color.argb(if(immersive)120 else 40,191,70,244)
            paint.shader=LinearGradient(0f,h*.2f,w,h*.8f,ribbonColours,null,Shader.TileMode.CLAMP)
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
            if(motion&&active&&isShown&&windowVisibility==View.VISIBLE){lastFrame=SystemClock.uptimeMillis();postInvalidateOnAnimation()}
        }
    }
    private fun ColorDrawableCompat()=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
    private fun sampleTint(bitmap:Bitmap){
        try{val small=Bitmap.createScaledBitmap(bitmap,1,1,true);val col=small.getPixel(0,0);coverTint=Color.rgb(max(70,Color.red(col)),max(70,Color.green(col)),max(100,Color.blue(col)));small.recycle()}catch(_:Exception){}
    }
    private inner class Control:TextView(this@MainActivity){
        private val brush=Paint(Paint.ANTI_ALIAS_FLAG)
        private val shape=Path()
        override fun onDraw(c:Canvas){
            val mark=text.toString();val transport=mark=="▶"||mark=="Ⅱ"||mark.contains("│")
            if(!transport){super.onDraw(c);return}
            val cx=width*.5f;val cy=height*.5f
            val main=height>=dp(80);val radius=if(main)dp(35).toFloat()else dp(21).toFloat()
            brush.shader=RadialGradient(cx-radius*.35f,cy-radius*.5f,radius*1.8f,intArrayOf(Color.rgb(157,166,255),Color.rgb(72,72,152),Color.rgb(42,39,88)),null,Shader.TileMode.CLAMP)
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

    override fun onDestroy(){ui.removeCallbacksAndMessages(null);player?.release();worker.shutdownNow();super.onDestroy()}
}
