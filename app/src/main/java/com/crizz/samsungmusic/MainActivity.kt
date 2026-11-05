package com.crizz.samsungmusic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
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
    data class Song(val id: Long, val title: String, val artist: String, val album: String, val duration: Long)
    private val songs = mutableListOf<Song>()
    private var shown = listOf<Song>()
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val covers = object : LruCache<Long, Bitmap>(16 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }
    private val loading = mutableSetOf<Long>()
    private val prefs by lazy { getSharedPreferences("appearance", MODE_PRIVATE) }
    private var theme = 0
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
    private val foreground get() = if (light) Color.rgb(29, 27, 34) else Color.rgb(249, 247, 251)
    private val secondary get() = if (light) Color.rgb(111, 104, 118) else Color.rgb(165, 159, 176)
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun label(value: String, size: Int = 16, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(foreground)
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
    private fun button(symbol: String, description: String, size: Int = 25, action: () -> Unit) = label(symbol, size).apply {
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
        theme = prefs.getInt("theme",0); light = prefs.getBoolean("light",false)
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
        top.addView(label("Music",36,true),LinearLayout.LayoutParams(0,dp(68),1f))
        top.addView(button("◉","Customise appearance",27) { customise() }.apply { setTextColor(accent) })
        body.addView(top)
        body.addView(label("Your sound. Your space.",14).apply { setTextColor(secondary) })
        body.addView(space(18))
        val search = EditText(this).apply {
            hint = "Search songs, artists, albums"; textSize = 15f; isSingleLine = true
            setTextColor(foreground); setHintTextColor(secondary)
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
        val empty=label("No music here yet. Add songs to your phone.",15).apply { gravity=Gravity.CENTER; maxLines=3; setTextColor(secondary) }
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
                setPadding(dp(14),0,dp(14),0); setTextColor(if(page==name)foreground else secondary)
                if(page==name) background=panel(20)
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
        adapter.notifyDataSetChanged()
    }
    private fun buildPlayer() {
        sheet=column().apply { setPadding(dp(28),dp(12),dp(28),dp(18)); background=panel(30,true); visibility=View.GONE; isClickable=true }
        val close=row(); close.addView(button("⌄","Close player",30){hidePlayer()})
        close.addView(label("NOW PLAYING",11,true).apply { gravity=Gravity.CENTER;letterSpacing=.16f },LinearLayout.LayoutParams(0,dp(48),1f))
        close.addView(button("⋯","Player options"){selected?.let{songOptions(it)}}); sheet.addView(close)
        sheet.addView(space(16))
        bigArt=art(280,22).apply { elevation=dp(16).toFloat() }
        sheet.addView(bigArt,LinearLayout.LayoutParams(-1,0,1f).apply{bottomMargin=dp(30)})
        val titleRow=row(); val info=column()
        bigTitle=label("Your next favourite",25,true);bigArtist=label("Choose a song from your library",17).apply{setTextColor(secondary)}
        info.addView(bigTitle);info.addView(bigArtist)
        titleRow.addView(info,LinearLayout.LayoutParams(0,-2,1f));love=button("♡","Toggle favourite",30){selected?.let{favourite(it)}}
        titleRow.addView(love);sheet.addView(titleRow);sheet.addView(space(18))
        seek=SeekBar(this).apply {
            progressTintList=ColorStateList.valueOf(accent);thumbTintList=ColorStateList.valueOf(foreground)
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
        root.addView(sheet,FrameLayout.LayoutParams(-1,-1).apply{topMargin=dp(8)})
        var down=0f
        close.setOnTouchListener { _,event -> when(event.actionMasked){MotionEvent.ACTION_DOWN->{down=event.rawY;true};MotionEvent.ACTION_MOVE->{sheet.translationY=max(0f,event.rawY-down);true};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{if(sheet.translationY>dp(80))hidePlayer()else sheet.animate().translationY(0f).setDuration(220).start();true};else->false} }
    }
    private fun showPlayer() {
        if(expanded)return
        expanded=true;sheet.visibility=View.VISIBLE;sheet.translationY=if(motion)root.height.toFloat() else 0f
        sheet.animate().translationY(0f).setDuration(if(motion)480 else 0).setInterpolator(DecelerateInterpolator(1.8f)).start()
    }
    private fun hidePlayer() {
        expanded=false
        sheet.animate().translationY(root.height.toFloat()).setDuration(if(motion)350 else 0).withEndAction{sheet.visibility=View.GONE}.start()
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
        val day=Switch(this).apply{text="Light appearance";setTextColor(foreground);isChecked=light;setOnCheckedChangeListener{_,v->light=v;saveAppearance();dialog?.dismiss();rebuild();customise()}}
        box.addView(day)
        box.addView(Switch(this).apply{text="Fluid animations";setTextColor(foreground);isChecked=motion;setOnCheckedChangeListener{_,v->motion=v;saveAppearance();backdrop.invalidate()}})
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
    private fun loadSongs(){
        worker.execute{
            val found=mutableListOf<Song>()
            try{contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,arrayOf("_id","title","artist","album","duration"),"is_music != 0",null,"title COLLATE NOCASE ASC")?.use{c->while(c.moveToNext())found.add(Song(c.getLong(0),c.getString(1)?:"Untitled",c.getString(2)?:"Unknown artist",c.getString(3)?:"Unknown album",c.getLong(4)))}}catch(_:Exception){}
            ui.post{if(!isDestroyed){songs.clear();songs.addAll(found);filter()}}
        }
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==42&&results.firstOrNull()==PackageManager.PERMISSION_GRANTED)loadSongs()else count.text="Music access is needed. Enable it in app settings."}
    private fun play(song:Song){
        player?.release();prepared=false;selected=song;position=0
        val fresh=MediaPlayer();player=fresh
        try{
            fresh.setDataSource(this,ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,song.id))
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
            try{retriever.setDataSource(this,ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,song.id));retriever.embeddedPicture?.let{bytes->
                val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                options.inSampleSize=max(1,max(options.outWidth,options.outHeight)/700);options.inJustDecodeBounds=false
                bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            }}catch(_:Exception){}finally{try{retriever.release()}catch(_:Exception){}}
            ui.post{loading.remove(song.id);bitmap?.let{b->covers.put(song.id,b);if(!isDestroyed){if(view.tag==song.id)view.setImageBitmap(b);if(selected?.id==song.id){miniArt.setImageBitmap(b);bigArt.setImageBitmap(b)};adapter.notifyDataSetChanged()}}}
        }
    }
    private inner class Atmosphere:View(this@MainActivity){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var start=SystemClock.uptimeMillis()
        override fun onDraw(c:Canvas){
            val w=width.toFloat();val h=height.toFloat();val t=if(motion)(SystemClock.uptimeMillis()-start)/10000f else 0f
            c.drawColor(if(light)Color.rgb(247,244,250)else Color.rgb(13,12,18))
            val col=palettes[theme]
            for(i in 0..1){val x=w*(if(i==0).15f else .9f)+sin(t+i)*w*.15f;val y=h*(if(i==0).12f else .55f)+cos(t+i)*h*.08f
                val tint=Color.argb(if(light)28 else 40,Color.red(col[i]),Color.green(col[i]),Color.blue(col[i]))
                paint.shader=RadialGradient(x,y,w*.95f,tint,Color.TRANSPARENT,Shader.TileMode.CLAMP);c.drawRect(0f,0f,w,h,paint)
            }
            paint.shader=null;if(motion&&isShown)postInvalidateOnAnimation()
        }
    }
    override fun onDestroy(){ui.removeCallbacksAndMessages(null);player?.release();worker.shutdownNow();super.onDestroy()}
}
