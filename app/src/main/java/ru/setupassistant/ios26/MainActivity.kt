package ru.setupassistant.ios26

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import dev.rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class MainActivity : ComponentActivity() {

    // ── Liquid Glass palette ──────────────────────────────────────────────
    private val glassTint = Color.argb(35, 255, 255, 255)
    private val glassStroke = Color.argb(60, 255, 255, 255)
    private val glassFill  = Color.argb(80, 30, 34, 42)
    private val bgTop = Color.rgb(18, 21, 28)
    private val bgBottom = Color.rgb(52, 58, 68)
    private val silver = Color.rgb(232, 236, 242)
    private val muted = Color.rgb(150, 160, 172)

    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var dock: LinearLayout
    private val apps = mutableListOf<LaunchApp>()
    private var page = 0
    private val handler = Handler(Looper.getMainLooper())
    private var batteryLevel = 100

    data class LaunchApp(val label: String, val pkg: String, val icon: android.graphics.drawable.Drawable)

    // ── Settings ──────────────────────────────────────────────────────────
    private var gridCols = 4
    private var showLabels = true
    private var iconSize = 52
    private var darkMode = true
    private var clock24h = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ios26_settings", MODE_PRIVATE)
        loadSettings()
        window.statusBarColor = bgTop
        window.navigationBarColor = bgTop
        apps.addAll(loadApps())
        buildShell()
        showPage(0)
        updateClock()
        // Update clock every second
        handler.post(object : Runnable {
            override fun run() {
                updateClock()
                handler.postDelayed(this, 1000)
            }
        })
    }

    // ── Settings ──────────────────────────────────────────────────────────
    private fun loadSettings() {
        gridCols = prefs.getInt("grid_cols", 4)
        showLabels = prefs.getBoolean("show_labels", true)
        iconSize = prefs.getInt("icon_size", 52)
        darkMode = prefs.getBoolean("dark_mode", true)
        clock24h = prefs.getBoolean("clock_24h", true)
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putInt("grid_cols", gridCols)
            putBoolean("show_labels", showLabels)
            putInt("icon_size", iconSize)
            putBoolean("dark_mode", darkMode)
            putBoolean("clock_24h", clock24h)
        }.apply()
    }

    // ── Apps ──────────────────────────────────────────────────────────────
    private fun loadApps(): List<LaunchApp> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName) null
                else LaunchApp(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm))
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    // ── UI construction ───────────────────────────────────────────────────
    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgTop)
        }
        // Background gradient
        val bgDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(bgTop, bgBottom, bgTop)
        ).apply { setShape(GradientDrawable.RECTANGLE) }
        root.background = bgDrawable

        // ── Header ──
        val header = buildHeader()
        root.addView(header)

        // ── Content area ──
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // ── Dock ──
        dock = buildDock()
        root.addView(dock)

        setContentView(root)
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(8))
        }
        val clock = TextView(this).apply {
            tag = "clock"
            textSize = 64f
            setTextColor(silver)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        header.addView(clock, LinearLayout.LayoutParams(MATCH, dp(92)))

        val date = TextView(this).apply {
            tag = "date"
            textSize = 12f
            letterSpacing = 0.14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(date)

        // Battery widget
        val battery = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        val batteryIcon = TextView(this).apply {
            tag = "battery_icon"
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        battery.addView(batteryIcon)
        val batteryText = TextView(this).apply {
            tag = "battery_text"
            textSize = 11f
            setTextColor(muted)
            setPadding(dp(6), 0, 0, 0)
        }
        battery.addView(batteryText)
        header.addView(battery)

        // Settings button
        val settingsBtn = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(silver)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { showSettingsDialog() }
        }
        header.addView(settingsBtn)

        return header
    }

    private fun buildDock(): LinearLayout {
        val dockContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(16))
            background = glassBackground(radius = dp(32))
        }
        // Dock apps: phone, sms, chrome, camera
        val dockApps = listOf("com.android.dialer", "com.google.android.apps.messaging",
            "com.android.chrome", "com.android.camera")
        dockApps.forEach { pkg ->
            val app = apps.firstOrNull { it.pkg == pkg }
                ?: apps.firstOrNull { it.pkg.contains(pkg.substringAfterLast('.').lowercase()) }
            app?.let { addDockIcon(it, dockContainer) }
        }
        return dockContainer
    }

    private fun addDockIcon(app: LaunchApp, parent: LinearLayout) {
        val icon = ImageView(this).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            isClickable = true
            setOnClickListener { launch(app.pkg) }
            // Glass background
            background = glassBackground(radius = dp(18))
        }
        val lp = LinearLayout.LayoutParams(dp(56), dp(56))
        lp.marginEnd = dp(8)
        parent.addView(icon, lp)
    }

    // ── Pages ─────────────────────────────────────────────────────────────
    private fun showPage(which: Int) {
        page = which
        content.removeAllViews()
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
        }
        when (which) {
            0 -> showHomePage(body)
            1 -> showAppsPage(body)
            2 -> showFoldersPage(body)
        }
        scroll.addView(body)
        content.addView(scroll)
    }

    private fun showHomePage(body: LinearLayout) {
        // Favourites
        sectionTitle(body, "FAVOURITES")
        val preferred = listOf(
            "org.telegram.messenger", "com.google.android.youtube",
            "com.zhiliaoapp.musically", "com.android.chrome",
            "com.google.android.apps.photos", "com.android.camera",
            "com.android.settings", "com.android.vending"
        )
        val selected = preferred.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg } }
        addAppGrid(body, selected.take(8), gridCols)
        // All apps
        sectionTitle(body, "ALL APPS")
        addAppGrid(body, apps.filter { a -> selected.none { it.pkg == a.pkg } }.take(20), gridCols)

        // Music widget
        addMusicWidget(body)
    }

    private fun showAppsPage(body: LinearLayout) {
        sectionTitle(body, "APP LIBRARY")
        val categories = listOf(
            "COMMUNICATION" to listOf("telegram", "whatsapp", "discord", "vk", "messaging", "dialer"),
            "SOCIAL" to listOf("tiktok", "instagram", "facebook", "twitter", "musically"),
            "GAMES" to listOf("game", "steam", "rust"),
            "GOOGLE & TOOLS" to listOf("google", "chrome", "drive", "maps", "files", "vpn"),
            "MEDIA" to listOf("youtube", "music", "spotify", "video", "gallery", "photos"),
            "SYSTEM" to listOf("settings", "camera", "calculator", "calendar")
        )
        val used = mutableSetOf<String>()
        categories.forEach { (name, keys) ->
            val group = apps.filter { app ->
                app.pkg.lowercase().let { pkg -> keys.any { pkg.contains(it) } } && app.pkg !in used
            }
            if (group.isNotEmpty()) {
                sectionTitle(body, name)
                addAppGrid(body, group, gridCols)
                used.addAll(group.map { it.pkg })
            }
        }
        val other = apps.filter { it.pkg !in used }
        if (other.isNotEmpty()) {
            sectionTitle(body, "OTHER")
            addAppGrid(body, other, gridCols)
        }
    }

    private fun showFoldersPage(body: LinearLayout) {
        sectionTitle(body, "FOLDERS")
        val folders = prefs.getStringSet("folders", emptySet())?.toList() ?: emptyList()
        if (folders.isEmpty()) {
            val hint = TextView(this).apply {
                text = "Нет папок. Создайте папку для группировки приложений."
                textSize = 12f
                setTextColor(muted)
                setPadding(dp(16), dp(24), dp(16), dp(8))
            }
            body.addView(hint)
        } else {
            folders.forEach { folder ->
                val appsInFolder = prefs.getStringSet("folder_$folder", emptySet())?.toList() ?: emptyList()
                val folderTile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = glassBackground(radius = dp(18))
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    isClickable = true
                    setOnClickListener { showFolderContent(folder, appsInFolder, body) }
                }
                val icon = TextView(this).apply {
                    text = "📁"
                    textSize = 32f
                    gravity = Gravity.CENTER
                }
                folderTile.addView(icon)
                val label = TextView(this).apply {
                    text = folder
                    textSize = 11f
                    setTextColor(silver)
                    maxLines = 1
                    gravity = Gravity.CENTER
                }
                folderTile.addView(label)
                val lp = LinearLayout.LayoutParams(0, dp(88), 1f)
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                body.addView(folderTile, lp)
            }
        }
        // Create folder button
        val createBtn = Button(this).apply {
            text = "+ СОЗДАТЬ ПАПКУ"
            textSize = 11f
            setOnClickListener { createFolderDialog() }
        }
        val lp = LinearLayout.LayoutParams(MATCH, dp(48))
        lp.setMargins(dp(16), dp(16), dp(16), dp(16))
        body.addView(createBtn, lp)
    }

    private fun showFolderContent(folder: String, appPkgs: List<String>, parent: LinearLayout) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(folder)
            .setItems(appPkgs.map { pkg -> apps.firstOrNull { it.pkg == pkg }?.label ?: pkg }.toTypedArray()) { _, which ->
                val pkg = appPkgs.getOrNull(which)
                if (pkg != null) launch(pkg)
            }
            .setNegativeButton("Закрыть", null)
        dialog.show()
    }

    private fun createFolderDialog() {
        val input = EditText(this).apply {
            hint = "Название папки"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Новая папка")
            .setView(input)
            .setPositiveButton("Создать") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val folders = (prefs.getStringSet("folders", emptySet()) ?: emptySet()).toMutableSet()
                    folders.add(name)
                    prefs.edit().putStringSet("folders", folders).apply()
                    showPage(2)
                    Toast.makeText(this, "Папка создана: $name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun addMusicWidget(body: LinearLayout) {
        sectionTitle(body, "MUSIC")
        val music = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassBackground(radius = dp(24))
            setPadding(dp(16))
        }
        val glyph = TextView(this).apply {
            text = "♫"
            textSize = 28f
            setTextColor(silver)
            gravity = Gravity.CENTER
        }
        music.addView(glyph, LinearLayout.LayoutParams(dp(48), dp(48)))
        val text = TextView(this).apply {
            text = "Музыкальный плеер\nОткройте ваше приложение"
            textSize = 12f
            setTextColor(silver)
            setPadding(dp(12), 0, 0, 0)
        }
        music.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
        val btn = Button(this).apply {
            text = "OPEN"
            textSize = 10f
            setOnClickListener { openFirstMatching(listOf("music", "spotify", "yandex", "deezer")) }
        }
        music.addView(btn)
        body.addView(music, LinearLayout.LayoutParams(MATCH, dp(84)))
    }

    // ── App grid with Liquid Glass tiles ─────────────────────────────────
    private fun addAppGrid(parent: LinearLayout, items: List<LaunchApp>, columns: Int) {
        if (items.isEmpty()) return
        val grid = GridLayout(this).apply {
            columnCount = columns
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        items.forEach { app ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                background = glassBackground(radius = dp(18))
                isClickable = true
                isFocusable = true
                setOnClickListener { launch(app.pkg) }
                setOnLongClickListener {
                    showAppInfo(app)
                    true
                }
            }
            // Icon with glass border
            val iconFrame = FrameLayout(this).apply {
                background = glassBackground(radius = dp(14))
            }
            val icon = ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            iconFrame.addView(icon, FrameLayout.LayoutParams(dp(iconSize), dp(iconSize)))
            tile.addView(iconFrame, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))

            if (showLabels) {
                val label = TextView(this).apply {
                    text = app.label
                    textSize = 10f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setTextColor(silver)
                    setPadding(0, dp(4), 0, 0)
                }
                tile.addView(label, LinearLayout.LayoutParams(MATCH, WRAP))
            }

            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = if (showLabels) dp(92) else dp(70)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            grid.addView(tile, lp)
        }
        parent.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    // ── Glass background helper ───────────────────────────────────────────
    private fun glassBackground(radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(glassFill)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), glassStroke)
            // Add subtle gradient for glass effect
            gradientType = GradientDrawable.LINEAR_GRADIENT
            angle = 135f.toInt()
            colors = intArrayOf(glassTint, glassFill)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private fun sectionTitle(parent: LinearLayout, text: String) {
        val t = TextView(this).apply {
            this.text = text
            textSize = 10f
            letterSpacing = 0.16f
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(14), dp(6), dp(8))
        }
        parent.addView(t)
    }

    private fun launch(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "Не удалось открыть приложение", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppInfo(app: LaunchApp) {
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setMessage("Пакет: ${app.pkg}")
            .setPositiveButton("Приложение") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${app.pkg}")
                })
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun openFirstMatching(keys: List<String>) {
        val match = apps.firstOrNull { app ->
            keys.any { app.label.lowercase().contains(it) || app.pkg.lowercase().contains(it) }
        }
        if (match != null) launch(match.pkg)
        else Toast.makeText(this, "Приложение не найдено", Toast.LENGTH_SHORT).show()
    }

    // ── Clock ─────────────────────────────────────────────────────────────
    private fun updateClock() {
        val clock = root.findViewWithTag<TextView>("clock") ?: return
        val date = root.findViewWithTag<TextView>("date") ?: return
        val batteryIcon = root.findViewWithTag<TextView>("battery_icon") ?: return
        val batteryText = root.findViewWithTag<TextView>("battery_text") ?: return
        val now = Calendar.getInstance()
        val timeFormat = if (clock24h) SimpleDateFormat("HH:mm", Locale.getDefault())
                        else SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE · d MMMM", Locale("ru"))
        clock.text = timeFormat.format(now.time)
        date.text = dateFormat.format(now.time).uppercase(Locale.getDefault())
        batteryIcon.text = "🔋"
        batteryText.text = "$batteryLevel%"
    }

    // ── Settings dialog ───────────────────────────────────────────────────
    private fun showSettingsDialog() {
        val options = arrayOf(
            "Размер сетки: ${gridCols} колонок",
            "Размер иконок: $iconSize dp",
            "Показывать подписи: ${if (showLabels) "Вкл" else "Выкл"}",
            "Тёмная тема: ${if (darkMode) "Вкл" else "Выкл"}",
            "24-часовой формат: ${if (clock24h) "Да" else "Нет"}",
            "Установить обои",
            "Проверить Shizuku",
            "О приложении"
        )
        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { gridCols = if (gridCols >= 5) 3 else gridCols + 1; saveSettings(); showPage(page) }
                    1 -> { iconSize = if (iconSize >= 60) 48 else iconSize + 4; saveSettings(); showPage(page) }
                    2 -> { showLabels = !showLabels; saveSettings(); showPage(page) }
                    3 -> { darkMode = !darkMode; saveSettings(); recreate() }
                    4 -> { clock24h = !clock24h; saveSettings() }
                    5 -> chooseWallpaper()
                    6 -> checkShizuku()
                    7 -> Toast.makeText(this, "iOS 26 Setup Assistant v1.0", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun chooseWallpaper() {
        val options = arrayOf("Встроенные обои", "Системные обои")
        AlertDialog.Builder(this)
            .setTitle("Обои")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> applyBuiltInWallpaper()
                    1 -> startActivity(Intent(Intent.ACTION_SET_WALLPAPER))
                }
            }
            .show()
    }

    private fun applyBuiltInWallpaper() {
        try {
            val drawable = getDrawable(R.drawable.silver_wallpaper)!!
            val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            WallpaperManager.getInstance(this).setBitmap(bitmap)
            Toast.makeText(this, "Обои установлены", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkShizuku() {
        try {
            val running = Shizuku.pingBinder()
            val msg = if (running)
                "Shizuku запущен. Расширенные функции доступны."
            else
                "Shizuku не запущен. Лаунчер работает в базовом режиме."
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Shizuku не установлен. Базовый режим.", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
