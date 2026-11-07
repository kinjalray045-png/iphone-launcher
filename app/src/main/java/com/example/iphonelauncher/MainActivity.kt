package com.example.iphonelauncher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private data class AppItem(
    val label: String,
    val component: ComponentName,
    val icon: Bitmap,
    val intent: Intent
)

/**
 * iOS-style home screen: 4-column icon grid with squircle icons, a dock,
 * page dots, a "Search" pill and a simple date/time widget on page one.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var searchOverlay: LinearLayout
    private lateinit var searchInput: EditText
    private val searchAdapter = SearchAdapter()

    private var apps: List<AppItem> = emptyList()
    private var dockApps: List<AppItem> = emptyList()
    private var signature = ""

    private var insetTop = 0
    private var insetBottom = 0
    private var built = false

    private var topPad = 0
    private var rowPitch = 0
    private var rows = 5
    private var dockH = 0
    private var dockBottom = 0

    // iPhone icons are roughly 15.5% of the screen width.
    private val iconSize: Int
        get() = (resources.displayMetrics.widthPixels * 0.155f).toInt()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        root = FrameLayout(this)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            insetTop = bars.top
            insetBottom = bars.bottom
            if (!built) {
                built = true
                buildUi()
            }
            searchOverlay.setPadding(0, insetTop + dp(12), 0, ime.bottom)
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while we are already visible.
        if (built) goHome()
    }

    override fun onResume() {
        super.onResume()
        if (built && launcherActivities().joinToString(",") { it.key() } != signature) refresh()
    }

    private fun goHome() {
        if (searchOverlay.visibility == View.VISIBLE) {
            closeSearch()
        } else if (pager.currentItem != 0) {
            pager.setCurrentItem(0, true)
        }
    }

    // ---------------------------------------------------------------- UI build

    private fun buildUi() {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels

        topPad = insetTop + dp(14)
        rowPitch = (iconSize * 1.62f).toInt()
        dockH = (iconSize * 1.42f).toInt()
        dockBottom = insetBottom / 2 + dp(8)
        val reservedBottom = dockBottom + dockH + dp(12 + 30 + 10 + 8 + 10)
        rows = maxOf(5, (h - topPad - reservedBottom) / rowPitch)

        // Pages
        pager = ViewPager2(this)
        (pager.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateDots(position)
        })
        root.addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))

        // Page dots
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        root.addView(
            dots,
            FrameLayout.LayoutParams(MATCH, dp(8), Gravity.BOTTOM).apply {
                bottomMargin = dockBottom + dockH + dp(12 + 30 + 10)
            }
        )

        // Search pill
        val pill = TextView(this).apply {
            text = "Search"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            compoundDrawablePadding = dp(6)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            setPadding(dp(16), 0, dp(18), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(15).toFloat()
                setColor(0x33FFFFFF)
            }
            setOnClickListener { openSearch() }
        }
        root.addView(
            pill,
            FrameLayout.LayoutParams(WRAP, dp(30), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dockBottom + dockH + dp(12)
            }
        )

        // Dock
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dockH * 0.34f
                setColor(0x40FFFFFF)
            }
        }
        val dockMargin = (w * 0.025f).toInt()
        root.addView(
            dock,
            FrameLayout.LayoutParams(MATCH, dockH, Gravity.BOTTOM).apply {
                leftMargin = dockMargin
                rightMargin = dockMargin
                bottomMargin = dockBottom
            }
        )

        // Search overlay
        searchOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        searchInput = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            setSingleLine()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x33FFFFFF)
            }
            doAfterTextChanged { updateSearch(it?.toString().orEmpty()) }
        }
        val list = ListView(this).apply {
            divider = null
            adapter = searchAdapter
        }
        searchOverlay.addView(
            searchInput,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(16), 0, dp(16), dp(8)) }
        )
        searchOverlay.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(searchOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        refresh()
    }

    private fun refresh() {
        loadApps()
        populateDock()
        rebuildPages()
    }

    private fun rebuildPages() {
        val firstCapacity = (rows - 2) * 4 // two rows are taken by the widget
        val perPage = rows * 4
        val pages = mutableListOf<List<AppItem>>(apps.take(firstCapacity))
        pages += apps.drop(firstCapacity).chunked(perPage)

        val previous = if (pager.adapter != null) pager.currentItem else 0
        pager.adapter = PagesAdapter(pages)
        pager.setCurrentItem(minOf(previous, pages.size - 1), false)
        buildDots(pages.size)
    }

    private fun buildDots(count: Int) {
        dots.removeAllViews()
        if (count <= 1) return
        repeat(count) {
            val d = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            }
            dots.addView(
                d,
                LinearLayout.LayoutParams(dp(7), dp(7)).apply { setMargins(dp(4), 0, dp(4), 0) }
            )
        }
        updateDots(pager.currentItem)
    }

    private fun updateDots(selected: Int) {
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).alpha = if (i == selected) 1f else 0.35f
        }
    }

    private fun populateDock() {
        dock.removeAllViews()
        dockApps.forEach { item ->
            val iv = ImageView(this).apply {
                setImageBitmap(item.icon)
                setOnClickListener { launch(item, this) }
                setOnLongClickListener { showMenu(this, item); true }
                pressFeedback()
            }
            val holder = FrameLayout(this)
            holder.addView(iv, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            dock.addView(holder, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
    }

    // ---------------------------------------------------------------- apps

    private fun launcherActivities(): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
    }

    private fun ResolveInfo.key() = activityInfo.packageName + "/" + activityInfo.name

    private fun loadApps() {
        val pm = packageManager
        val all = launcherActivities()

        val dockBases = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        )
        val dockPairs = dockBases.mapNotNull { base ->
            pm.queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY)
                .firstOrNull()?.let { it to base }
        }.distinctBy { it.first.activityInfo.packageName }

        val dockPackages = dockPairs.map { it.first.activityInfo.packageName }.toSet()
        dockApps = dockPairs.map { (info, base) -> toItem(info, base) }
        apps = all
            .filter { it.activityInfo.packageName !in dockPackages }
            .map { toItem(it, null) }
            .sortedBy { it.label.lowercase() }
        signature = all.joinToString(",") { it.key() }
    }

    private fun toItem(info: ResolveInfo, base: Intent?): AppItem {
        val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
        val intent = (base?.let { Intent(it) }
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)).apply {
            this.component = component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        return AppItem(
            label = info.loadLabel(packageManager).toString(),
            component = component,
            icon = squircleIcon(info.loadIcon(packageManager), iconSize),
            intent = intent
        )
    }

    private fun launch(item: AppItem, anchor: View) {
        try {
            val options = ActivityOptions.makeScaleUpAnimation(
                anchor, 0, 0, anchor.width, anchor.height
            )
            startActivity(item.intent, options.toBundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${item.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu(anchor: View, item: AppItem) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "App Info")
        menu.menu.add(0, 2, 1, "Remove App")
        menu.setOnMenuItemClickListener { mi ->
            val uri = Uri.parse("package:${item.component.packageName}")
            try {
                when (mi.itemId) {
                    1 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                    2 -> startActivity(Intent(Intent.ACTION_DELETE, uri))
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show()
            }
            true
        }
        menu.show()
    }

    // ---------------------------------------------------------------- icons

    /** iOS app-icon outline: a superellipse (n ~ 5) rather than a plain rounded rect. */
    private fun squirclePath(size: Float): Path {
        val path = Path()
        val r = size / 2f
        val n = 5.0
        val steps = 160
        for (i in 0..steps) {
            val t = 2.0 * Math.PI * i / steps
            val c = Math.cos(t)
            val s = Math.sin(t)
            val x = r + r * Math.signum(c) * Math.pow(Math.abs(c), 2.0 / n)
            val y = r + r * Math.signum(s) * Math.pow(Math.abs(s), 2.0 / n)
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close()
        return path
    }

    private fun squircleIcon(drawable: Drawable, size: Int): Bitmap {
        val content = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cc = Canvas(content)
        if (drawable is AdaptiveIconDrawable) {
            // Only the central 72/108 of an adaptive icon is the visible area,
            // so scale the layers up to fill the whole squircle.
            val big = (size * 1.5f).toInt()
            val off = -(big - size) / 2
            drawable.background?.let {
                it.setBounds(off, off, off + big, off + big)
                it.draw(cc)
            }
            drawable.foreground?.let {
                it.setBounds(off, off, off + big, off + big)
                it.draw(cc)
            }
        } else {
            cc.drawColor(0xFFF2F2F7.toInt())
            val inset = (size * 0.12f).toInt()
            drawable.setBounds(inset, inset, size - inset, size - inset)
            drawable.draw(cc)
        }

        // Anti-aliased mask.
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        oc.drawPath(squirclePath(size.toFloat()), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        oc.drawBitmap(content, 0f, 0f, paint)
        return out
    }

    // ---------------------------------------------------------------- search

    private fun updateSearch(query: String) {
        val all = (apps + dockApps).sortedBy { it.label.lowercase() }
        val q = query.trim()
        searchAdapter.submit(if (q.isEmpty()) all else all.filter { it.label.contains(q, ignoreCase = true) })
    }

    private fun openSearch() {
        updateSearch("")
        searchOverlay.alpha = 0f
        searchOverlay.visibility = View.VISIBLE
        searchOverlay.animate().alpha(1f).setDuration(180).start()
        searchInput.requestFocus()
        WindowCompat.getInsetsController(window, searchInput).show(WindowInsetsCompat.Type.ime())
    }

    private fun closeSearch() {
        if (searchOverlay.visibility != View.VISIBLE) return
        WindowCompat.getInsetsController(window, searchInput).hide(WindowInsetsCompat.Type.ime())
        searchInput.setText("")
        searchOverlay.visibility = View.GONE
    }

    private inner class SearchAdapter : BaseAdapter() {
        private var data: List<AppItem> = emptyList()

        fun submit(list: List<AppItem>) {
            data = list
            notifyDataSetChanged()
        }

        override fun getCount() = data.size
        override fun getItem(position: Int): Any = data[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = data[position]
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            val iv = ImageView(this@MainActivity).apply { setImageBitmap(item.icon) }
            val tv = TextView(this@MainActivity).apply {
                text = item.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            }
            row.addView(iv, LinearLayout.LayoutParams(dp(44), dp(44)))
            row.addView(tv, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(14) })
            row.setOnClickListener {
                launch(item, iv)
                closeSearch()
            }
            return row
        }
    }

    // ---------------------------------------------------------------- pages

    private inner class PagesAdapter(private val pages: List<List<AppItem>>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = pages.size
        override fun getItemViewType(position: Int) = if (position == 0) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val grid = IconGrid(parent.context, withWidget = viewType == 1)
            grid.layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            return object : RecyclerView.ViewHolder(grid) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as IconGrid).bind(pages[position])
        }
    }

    /** One home-screen page: optional widget on top, then icons in a 4-column grid. */
    private inner class IconGrid(context: android.content.Context, private val withWidget: Boolean) :
        ViewGroup(context) {

        fun bind(items: List<AppItem>) {
            removeAllViews()
            if (withWidget) addView(buildWidget())
            items.forEach { addView(makeCell(it)) }
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val pitchX = w / 4
            val marginX = (pitchX - iconSize) / 2
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (withWidget && i == 0) {
                    c.measure(
                        MeasureSpec.makeMeasureSpec(w - 2 * marginX, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(rowPitch * 2 - dp(14), MeasureSpec.EXACTLY)
                    )
                } else {
                    c.measure(
                        MeasureSpec.makeMeasureSpec(pitchX, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(rowPitch, MeasureSpec.EXACTLY)
                    )
                }
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val pitchX = w / 4
            var row = 0
            var col = 0
            var i = 0
            if (withWidget && childCount > 0) {
                val marginX = (pitchX - iconSize) / 2
                getChildAt(0).layout(marginX, topPad, w - marginX, topPad + rowPitch * 2 - dp(14))
                row = 2
                i = 1
            }
            while (i < childCount) {
                val left = col * pitchX
                val top = topPad + row * rowPitch
                getChildAt(i).layout(left, top, left + pitchX, top + rowPitch)
                col++
                if (col == 4) {
                    col = 0
                    row++
                }
                i++
            }
        }
    }

    private fun makeCell(item: AppItem): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val iv = ImageView(this).apply { setImageBitmap(item.icon) }
        cell.addView(iv, LinearLayout.LayoutParams(iconSize, iconSize))
        val tv = TextView(this).apply {
            text = item.label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, 0x66000000)
            setPadding(dp(2), dp(5), dp(2), 0)
        }
        cell.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
        cell.setOnClickListener { launch(item, iv) }
        cell.setOnLongClickListener { showMenu(it, item); true }
        cell.pressFeedback()
        return cell
    }

    private fun buildWidget(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), 0, dp(22), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(0x40000000)
            }
        }
        val time = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        box.addView(time, LinearLayout.LayoutParams(WRAP, WRAP))
        box.addView(date, LinearLayout.LayoutParams(WRAP, WRAP))
        return box
    }

    private fun View.pressFeedback() {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }
}
