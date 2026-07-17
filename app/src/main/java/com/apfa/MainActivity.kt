package com.apfa

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * aPFA setup screen.
 *
 * Pure UI shell — picks the MIDI + soundfont and captures the Voice Count and
 * Note Speed settings. Settings and the chosen soundfont persist across
 * launches via SharedPreferences. The native engine (libapfa.so) does the work.
 */
class MainActivity : Activity() {

    companion object {
        init {
            // Load BASS + BASSMIDI first so libapfa.so resolves them.
            System.loadLibrary("bass")
            System.loadLibrary("bassmidi")
            System.loadLibrary("apfa")
        }
        private const val REQ_MIDI = 1
        private const val REQ_SOUNDFONT = 2
        private const val REQ_BG_IMAGE = 3
        private const val REQ_EXPORT_PROFILE = 4
        private const val REQ_IMPORT_PROFILE = 5
        private const val REQ_STORAGE_PERM = 100
    }

    // Setup state — persisted across launches, handed to the engine on playback.
    private var midiUri: Uri? = null
    private var soundfontUri: Uri? = null
    private var voiceCount = 250
    private var noteSpeed = 0.05f
    // Bitmask of CPUs the engine is allowed to use. 0 = "Auto" (engine picks the
    // fastest core itself, see chooseBigCore in engine.cpp).
    private var cpuMask: Long = 0L
    // Use the ES2 "Legacy Renderer (GLES 2.0)" instead of the default ES3
    // renderer. Required on ES2-only GPUs (e.g. Mali-400 / MT6570) that cannot
    // create an ES3 context.
    private var legacyRenderer = false
    // "Chunked Disk Streaming" (Advanced Settings): allow the chunked on-disk
    // pagefile sort for MIDIs whose load transient exceeds RAM (~80M+ notes).
    // Streaming itself stays automatic either way (RAM-fit prediction / crash
    // marker); without this, beyond-ceiling MIDIs are refused with a message
    // pointing here. Pref/extra key stays "diskStreaming" for settings compat.
    private var chunkedStreaming = false
    // Background color in PFA BGR format (R=bit0, G=bit8, B=bit16). Default = PFA's 0x464646.
    private var bgColor: Int = 0x00464646
    // Optional background image path (in filesDir). When set, it overrides bgColor
    // during playback. Cleared when the user picks a solid colour instead.
    private var bgImagePath: String? = null
    // Profile waiting to be written out by the ACTION_CREATE_DOCUMENT result.
    private var pendingExport: JSONObject? = null

    private lateinit var midiButton: Button
    private lateinit var soundfontButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadSettings()
        setContentView(buildSetupScreen())
        ensureStoragePermission()
    }

    // Old devices (and some OEM pickers, e.g. Huawei EMUI) return file:// URIs from
    // the document picker. Opening those reads the raw /storage path, which needs
    // READ_EXTERNAL_STORAGE. Modern phones (API 33+) always get content:// — no
    // permission needed, and the permission no longer exists — so we only ask on 23..32.
    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT in 23..32) {
            val perm = Manifest.permission.READ_EXTERNAL_STORAGE
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(perm), REQ_STORAGE_PERM)
            }
        }
    }

    private fun buildSetupScreen(): View {
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = FrameLayout(this)

        // --- background (apfa-wp.jpg from assets; dark fallback if absent) ---
        val bg = ImageView(this)
        bg.scaleType = ImageView.ScaleType.CENTER_CROP
        bg.layoutParams = FrameLayout.LayoutParams(mp, mp)
        val bgBitmap = loadAsset("apfa-wp.jpg")
        if (bgBitmap != null) bg.setImageBitmap(bgBitmap)
        else bg.setBackgroundColor(Color.rgb(18, 18, 24))
        root.addView(bg)

        // --- top-left: logo + version ---
        val topLeft = LinearLayout(this)
        topLeft.orientation = LinearLayout.HORIZONTAL
        topLeft.gravity = Gravity.CENTER_VERTICAL
        topLeft.layoutParams = FrameLayout.LayoutParams(wc, wc).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dp(20), dp(20), 0, 0)
        }
        loadAsset("aPFAlogo.png")?.let { logo ->
            val iv = ImageView(this)
            iv.setImageBitmap(logo)
            iv.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            topLeft.addView(iv)
        }
        val version = TextView(this)
        // ES2/ES3 are now one build (renderer chosen by the Legacy toggle), so the
        // old "-ES2" fork suffix is gone.
        version.text = "aPFA v" + appVersion()
        version.setTextColor(Color.WHITE)
        version.textSize = 20f
        version.setShadowLayer(4f, 0f, 2f, Color.BLACK)
        version.setPadding(dp(12), 0, 0, 0)
        topLeft.addView(version)
        root.addView(topLeft)

        // --- bottom-left: byline ---
        val byline = TextView(this)
        byline.text = "By Starzainia and HexagonMIDIs!"
        byline.setTextColor(Color.WHITE)
        byline.textSize = 16f
        byline.setShadowLayer(4f, 0f, 2f, Color.BLACK)
        root.addView(byline, FrameLayout.LayoutParams(wc, wc).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(dp(20), 0, 0, dp(20))
        })

        // --- top-right: Core Affinity gear ---
        val gear = Button(this).apply {
            text = "⚙"
            textSize = 22f
            contentDescription = "Core Affinity"
            setOnClickListener { showCoreAffinityDialog() }
        }
        root.addView(gear, FrameLayout.LayoutParams(wc, wc).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(20), dp(20), 0)
        })

        // --- mid-left: actions ---
        val midLeft = LinearLayout(this)
        midLeft.orientation = LinearLayout.VERTICAL
        midLeft.layoutParams = FrameLayout.LayoutParams(wc, wc).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setMargins(dp(20), 0, 0, 0)
        }
        midiButton = Button(this)
        midiButton.text = "Play MIDI"
        midiButton.setOnClickListener { pickFile(REQ_MIDI) }
        midLeft.addView(midiButton)

        soundfontButton = Button(this)
        soundfontButton.text = soundfontUri?.let { "SF: " + displayName(it) } ?: "Load Soundfont"
        soundfontButton.setOnClickListener { pickFile(REQ_SOUNDFONT) }
        midLeft.addView(soundfontButton, LinearLayout.LayoutParams(wc, wc).apply {
            topMargin = dp(12)
        })
        root.addView(midLeft)

        // --- bottom-right: settings ---
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Color.argb(140, 0, 0, 0))
        panel.setPadding(dp(16), dp(12), dp(16), dp(12))
        panel.layoutParams = FrameLayout.LayoutParams(dp(280), wc).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            setMargins(0, 0, dp(20), dp(20))
        }

        // --- Background Color button (above Voice slider) ---
        val colorButton = Button(this)
        colorButton.text = "Background"
        colorButton.setOnClickListener { showBgColorDialog() }
        panel.addView(colorButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        val voiceLabel = label("Voice Count: $voiceCount")
        panel.addView(voiceLabel)
        val voiceBar = SeekBar(this)
        voiceBar.max = 500
        voiceBar.progress = voiceCount
        voiceBar.setOnSeekBarChangeListener(simpleListener { p ->
            voiceCount = p.coerceAtLeast(1)
            voiceLabel.text = "Voice Count: $voiceCount"
        })
        panel.addView(voiceBar)

        val speedLabel = label("Note Speed: %.3f".format(noteSpeed))
        speedLabel.layoutParams = LinearLayout.LayoutParams(wc, wc).apply { topMargin = dp(10) }
        panel.addView(speedLabel)
        val speedBar = SeekBar(this)
        speedBar.max = 1000                                  // non-linear, 0.05 at the midpoint
        speedBar.progress = progressFromSpeed(noteSpeed)
        speedBar.setOnSeekBarChangeListener(simpleListener { p ->
            noteSpeed = speedFromProgress(p)
            speedLabel.text = "Note Speed: %.3f".format(noteSpeed)
        })
        panel.addView(speedBar)

        // --- Advanced Settings (Legacy Renderer + Chunked Disk Streaming) ---
        val advancedButton = Button(this)
        advancedButton.text = "Advanced Settings"
        advancedButton.setOnClickListener { showAdvancedSettingsDialog() }
        panel.addView(advancedButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        root.addView(panel)

        return root
    }

    // --- Advanced Settings dialog ---------------------------------------------
    // Legacy Renderer: ES2 fallback for GPUs (Mali-400 / MT6570) that can't
    // create an ES3 context. Chunked Disk Streaming: allow the on-disk chunked
    // pagefile sort for MIDIs too big even for normal (automatic) disk
    // streaming — the one hop between little Timmy and a 12 GB pagefile.
    private fun showAdvancedSettingsDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(20), dp(10), dp(20), dp(10))

        val legacyBox = CheckBox(this)
        legacyBox.text = "Legacy Renderer (GLES 2.0)"
        legacyBox.isChecked = legacyRenderer
        legacyBox.setOnCheckedChangeListener { _, checked ->
            legacyRenderer = checked
            saveSettings()
        }
        container.addView(legacyBox)

        val streamBox = CheckBox(this)
        streamBox.text = "Chunked Disk Streaming"
        streamBox.isChecked = chunkedStreaming
        streamBox.setOnCheckedChangeListener { _, checked ->
            if (checked && !chunkedStreaming) {
                AlertDialog.Builder(this)
                    .setTitle("Chunked Disk Streaming")
                    .setMessage("Sorts a huge MIDI's pagefile in chunks on " +
                                "disk instead of in RAM, so Black MIDIs too " +
                                "big for normal disk streaming can load, " +
                                "the only limit is your free storage.\n\n" +
                                "WARNING: loading such MIDIs can take VERY " +
                                "large amounts of storage, pagefile lag " +
                                "will be possible, and stability issues " +
                                "may occur.\n\n" +
                                "Are you sure you want to enable this?")
                    .setPositiveButton("Yes") { _, _ ->
                        chunkedStreaming = true
                        saveSettings()
                    }
                    .setNegativeButton("No") { _, _ ->
                        streamBox.isChecked = false
                    }
                    .setOnCancelListener {
                        streamBox.isChecked = false
                    }
                    .show()
            } else if (!checked) {
                chunkedStreaming = false
                saveSettings()
            }
        }
        container.addView(streamBox)

        AlertDialog.Builder(this)
            .setTitle("Advanced Settings")
            .setView(container)
            .setPositiveButton("Done", null)
            .show()
    }

    // --- Note Speed slider: non-linear so the 0.05 default sits at the midpoint ---
    // progress 0..500   -> noteSpeed 0.005..0.05
    // progress 500..1000-> noteSpeed 0.05 ..1.0
    private fun speedFromProgress(p: Int): Float =
        if (p <= 500) 0.005f + (p / 500f) * 0.045f
        else          0.05f + ((p - 500) / 500f) * 0.95f

    private fun progressFromSpeed(s: Float): Int =
        if (s <= 0.05f) (((s - 0.005f) / 0.045f) * 500f).toInt().coerceIn(0, 500)
        else            (500 + ((s - 0.05f) / 0.95f) * 500f).toInt().coerceIn(500, 1000)

    // --- Core Affinity dialog -------------------------------------------------
    // Lists every cpuN dir under /sys/devices/system/cpu with its cpuinfo_max_freq.
    // Multi-select; saves the chosen set as a 64-bit mask. Empty = "Auto".

    private data class CpuRow(val index: Int, val maxKHz: Long)

    private fun readCpuList(): List<CpuRow> {
        val dir = File("/sys/devices/system/cpu")
        val entries = dir.listFiles { f ->
            f.isDirectory && f.name.matches(Regex("cpu[0-9]+"))
        } ?: return emptyList()
        return entries
            .mapNotNull { f ->
                val idx = f.name.substring(3).toIntOrNull() ?: return@mapNotNull null
                val freqFile = File(f, "cpufreq/cpuinfo_max_freq")
                val khz = try {
                    if (freqFile.exists()) freqFile.readText().trim().toLong() else 0L
                } catch (e: Exception) { 0L }
                CpuRow(idx, khz)
            }
            .sortedBy { it.index }
    }

    private fun formatCpuRow(row: CpuRow): String =
        if (row.maxKHz > 0)
            "cpu%d  —  %.2f GHz".format(row.index, row.maxKHz / 1_000_000.0)
        else
            "cpu%d  —  (offline)".format(row.index)

    private fun showCoreAffinityDialog() {
        val cpus = readCpuList()
        if (cpus.isEmpty()) {
            Toast.makeText(this, "No CPU info available", Toast.LENGTH_SHORT).show()
            return
        }
        val labels  = cpus.map { formatCpuRow(it) }.toTypedArray()
        val checked = BooleanArray(cpus.size) { i ->
            ((cpuMask shr cpus[i].index) and 1L) == 1L
        }
        AlertDialog.Builder(this)
            .setTitle("Core Affinity")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                var m = 0L
                for (i in cpus.indices)
                    if (checked[i]) m = m or (1L shl cpus[i].index)
                cpuMask = m
                saveSettings()
                val msg = if (m == 0L) "Core Affinity: Auto"
                          else "Core Affinity: " + cpus
                              .filter { ((m shr it.index) and 1L) == 1L }
                              .joinToString(", ") { "cpu${it.index}" }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Auto") { _, _ ->
                cpuMask = 0L
                saveSettings()
                Toast.makeText(this, "Core Affinity: Auto", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Background dialog: custom RGB / Hue-Sat-Lum colour + profiles + image ----
    // No presets. The user dials in a colour with R,G,B and Hue/Sat/Lum text boxes
    // (the two groups stay in sync), names + saves it as a profile, and can export
    // a profile to a file to carry to another device. "I want an Image!" picks a
    // .png/.jpg to use as the playback background instead of a solid colour.
    //
    // Colours are stored in PFA BGR format (R=bit0, G=bit8, B=bit16). The Hue/Sat/
    // Lum fields use PFA's exact HSV maths (PFA labels Value "Lum"); see Misc.cpp.

    // PFA Util::RGBtoHSV (Misc.cpp:200). Returns H 0..359, S/V 0..100.
    private fun rgbToHsv(R: Int, G: Int, B: Int): Triple<Int, Int, Int> {
        val dR = R / 255.0; val dG = G / 255.0; val dB = B / 255.0
        val M = maxOf(dR, dG, dB); val m = minOf(dR, dG, dB); val C = M - m
        var dH = when {
            C == 0.0 -> 0.0
            M == dR  -> (dG - dB) / C
            M == dG  -> (dB - dR) / C + 2.0
            else     -> (dR - dG) / C + 4.0
        }
        if (dH < 0) dH += 6.0
        val dV = M; val dS = if (dV > 0.0) C / dV else 0.0
        return Triple(((dH * 60.0 + 0.5).toInt()) % 360,
                      (dS * 100.0 + 0.5).toInt(),
                      (dV * 100.0 + 0.5).toInt())
    }

    // PFA Util::HSVtoRGB (Misc.cpp:220). H 0..359, S/V 0..100 -> R,G,B 0..255.
    private fun hsvToRgb(H: Int, S: Int, V: Int): Triple<Int, Int, Int> {
        val dH = H / 60.0; val dS = S / 100.0; val dV = V / 100.0
        val C = dV * dS; val mm = dV - C
        var r1 = 0.0; var g1 = 0.0; var b1 = 0.0
        when {
            dH < 1.0 -> { r1 = C;               g1 = C * dH;        b1 = 0.0 }
            dH < 2.0 -> { r1 = C * (2.0 - dH);  g1 = C;             b1 = 0.0 }
            dH < 3.0 -> { r1 = 0.0;             g1 = C;             b1 = C * (dH - 2.0) }
            dH < 4.0 -> { r1 = 0.0;             g1 = C * (4.0 - dH); b1 = C }
            dH < 5.0 -> { r1 = C * (dH - 4.0);  g1 = 0.0;           b1 = C }
            else     -> { r1 = C;               g1 = 0.0;           b1 = C * (6.0 - dH) }
        }
        return Triple(((r1 + mm) * 255.0 + 0.5).toInt(),
                      ((g1 + mm) * 255.0 + 0.5).toInt(),
                      ((b1 + mm) * 255.0 + 0.5).toInt())
    }

    private fun showBgColorDialog() {
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val mp = ViewGroup.LayoutParams.MATCH_PARENT

        // Current colour seeds the fields.
        var R = bgColor and 0xFF
        var G = (bgColor shr 8) and 0xFF
        var B = (bgColor shr 16) and 0xFF

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val swatch = View(this)
        root.addView(swatch, LinearLayout.LayoutParams(mp, dp(40)))
        root.addView(dialogLabel("Tap the colour above to apply it (switches off any image)").apply {
            textSize = 12f
        }, LinearLayout.LayoutParams(mp, wc).apply { bottomMargin = dp(12) })

        val rEt = colorField(); val gEt = colorField(); val bEt = colorField()
        val hEt = colorField(); val sEt = colorField(); val lEt = colorField()
        root.addView(colorRow("R", rEt, "G", gEt, "B", bEt))
        root.addView(colorRow("Hue", hEt, "Sat", sEt, "Lum", lEt))

        val restoreBtn = Button(this).apply { text = "Restore to Default (PFA Grey)" }
        root.addView(restoreBtn, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(8) })

        var updating = false
        fun applySwatch() = swatch.setBackgroundColor(Color.rgb(R, G, B))
        fun pushRgb() { updating = true; rEt.setText(R.toString()); gEt.setText(G.toString()); bEt.setText(B.toString()); updating = false }
        fun pushHsv() { val (h, s, v) = rgbToHsv(R, G, B); updating = true; hEt.setText(h.toString()); sEt.setText(s.toString()); lEt.setText(v.toString()); updating = false }
        // Commit the current R,G,B as the solid background, dropping any image.
        fun commitColor() {
            bgColor = (R and 0xFF) or ((G and 0xFF) shl 8) or ((B and 0xFF) shl 16)
            bgImagePath = null            // picking a colour drops any image
            saveSettings()
            toast("Background colour set")
        }

        val onRgb = {
            R = clampField(rEt, 0, 255); G = clampField(gEt, 0, 255); B = clampField(bEt, 0, 255)
            pushHsv(); applySwatch()
        }
        val onHsv = {
            val (nr, ng, nb) = hsvToRgb(clampField(hEt, 0, 359), clampField(sEt, 0, 100), clampField(lEt, 0, 100))
            R = nr; G = ng; B = nb; pushRgb(); applySwatch()
        }
        for (e in listOf(rEt, gEt, bEt)) watch(e) { if (!updating) onRgb() }
        for (e in listOf(hEt, sEt, lEt)) watch(e) { if (!updating) onHsv() }
        pushRgb(); pushHsv(); applySwatch()

        // --- profile name + buttons ---
        val nameEt = EditText(this).apply {
            hint = "Profile name"; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
        }
        root.addView(dialogLabel("Save this colour as a named profile:"),
            LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(8) })
        root.addView(nameEt)

        root.addView(Button(this).apply {
            text = "Save Profile"
            setOnClickListener {
                val nm = nameEt.text.toString().trim()
                if (nm.isEmpty()) { toast("Enter a profile name first"); return@setOnClickListener }
                saveProfile(nm, R, G, B); toast("Saved profile \"$nm\"")
            }
        }, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(4) })

        val applyColor: (Int, Int, Int) -> Unit = { r, g, b -> R = r; G = g; B = b; pushRgb(); pushHsv(); applySwatch() }
        root.addView(Button(this).apply {
            text = "Load Profile"
            setOnClickListener { showLoadProfileDialog(applyColor) }
        }, LinearLayout.LayoutParams(mp, wc))
        root.addView(Button(this).apply {
            text = "Export Profile to File"
            setOnClickListener { showExportProfileDialog() }
        }, LinearLayout.LayoutParams(mp, wc))

        val imageBtn = Button(this).apply { text = "I want an Image!" }
        root.addView(imageBtn, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(8) })

        val scroll = ScrollView(this).apply { addView(root) }
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Background")
            .setView(scroll)
            .setPositiveButton("Apply Colour") { _, _ -> commitColor() }
            .setNegativeButton("Cancel", null)
            .create()
        // Tapping the swatch applies the current colour and closes — the quick way
        // to swap an image back for a solid colour.
        swatch.setOnClickListener { commitColor(); dlg.dismiss() }
        // Restore the standard PFA grey (0x464646) and apply it immediately.
        restoreBtn.setOnClickListener {
            R = 0x46; G = 0x46; B = 0x46
            pushRgb(); pushHsv(); applySwatch()
            commitColor(); dlg.dismiss()
        }
        imageBtn.setOnClickListener { dlg.dismiss(); pickFile(REQ_BG_IMAGE, "image/*") }
        dlg.show()
    }

    // --- colour-dialog view helpers ---

    private fun colorField(): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        textSize = 16f
        setTextColor(Color.WHITE)
        setSelectAllOnFocus(true)
    }

    private fun dialogLabel(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 14f
    }

    private fun colorRow(l1: String, e1: EditText, l2: String, e2: EditText,
                         l3: String, e3: EditText): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun cell(lbl: String, et: EditText) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(dialogLabel(lbl))
            col.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            })
        }
        cell(l1, e1); cell(l2, e2); cell(l3, e3)
        return row
    }

    private fun watch(et: EditText, onChange: () -> Unit) {
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = onChange()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun clampField(et: EditText, lo: Int, hi: Int): Int =
        (et.text.toString().toIntOrNull() ?: lo).coerceIn(lo, hi)

    // --- colour profiles (stored as JSON in SharedPreferences) -------------------

    private fun loadProfiles(): JSONArray = try {
        JSONArray(getPreferences(MODE_PRIVATE).getString("colorProfiles", "[]"))
    } catch (e: Exception) { JSONArray() }

    private fun profileJson(name: String, R: Int, G: Int, B: Int): JSONObject {
        val (h, s, v) = rgbToHsv(R, G, B)
        return JSONObject().apply {
            put("name", name); put("r", R); put("g", G); put("b", B)
            put("hue", h); put("sat", s); put("lum", v)
        }
    }

    private fun saveProfile(name: String, R: Int, G: Int, B: Int) {
        val arr = loadProfiles(); val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("name") != name) out.put(o)   // replace same-named
        }
        out.put(profileJson(name, R, G, B))
        getPreferences(MODE_PRIVATE).edit().putString("colorProfiles", out.toString()).apply()
    }

    private fun showLoadProfileDialog(onPick: (Int, Int, Int) -> Unit) {
        val arr = loadProfiles()
        val items = ArrayList<String>().apply {
            add("Import from file…")
            for (i in 0 until arr.length()) add(arr.getJSONObject(i).optString("name"))
        }
        AlertDialog.Builder(this)
            .setTitle("Load Profile")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) { pickFile(REQ_IMPORT_PROFILE); return@setItems }
                val o = arr.getJSONObject(which - 1)
                onPick(o.optInt("r"), o.optInt("g"), o.optInt("b"))
                toast("Loaded \"${o.optString("name")}\"")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExportProfileDialog() {
        if (Build.VERSION.SDK_INT < 19) {
            // ACTION_CREATE_DOCUMENT is SAF; profiles still import via the browser.
            toast("Export requires Android 4.4+")
            return
        }
        val arr = loadProfiles()
        if (arr.length() == 0) { toast("No saved profiles to export"); return }
        val names = Array(arr.length()) { arr.getJSONObject(it).optString("name") }
        AlertDialog.Builder(this)
            .setTitle("Export Profile to File")
            .setItems(names) { _, which ->
                pendingExport = arr.getJSONObject(which)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, names[which] + ".apfacolor.json")
                }
                startActivityForResult(intent, REQ_EXPORT_PROFILE)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- settings persistence ---

    private fun loadSettings() {
        val p = getPreferences(MODE_PRIVATE)
        voiceCount = p.getInt("voiceCount", voiceCount)
        noteSpeed  = p.getFloat("noteSpeed", noteSpeed)
        cpuMask    = p.getLong("cpuMask", cpuMask)
        legacyRenderer = p.getBoolean("legacyRenderer", legacyRenderer)
        chunkedStreaming = p.getBoolean("diskStreaming", chunkedStreaming)
        bgColor    = p.getInt("bgColor", bgColor)
        bgImagePath = p.getString("bgImage", null)?.takeIf { File(it).exists() }
        p.getString("soundfont", null)?.let { soundfontUri = Uri.parse(it) }
    }

    private fun saveSettings() {
        getPreferences(MODE_PRIVATE).edit()
            .putInt("voiceCount", voiceCount)
            .putFloat("noteSpeed", noteSpeed)
            .putLong("cpuMask", cpuMask)
            .putBoolean("legacyRenderer", legacyRenderer)
            .putBoolean("diskStreaming", chunkedStreaming)
            .putInt("bgColor", bgColor)
            .putString("bgImage", bgImagePath)
            .putString("soundfont", soundfontUri?.toString())
            .apply()
    }

    // --- helpers ---

    private fun label(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.textSize = 15f
        return tv
    }

    private fun simpleListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) { saveSettings() }
    }

    private fun loadAsset(name: String): Bitmap? = try {
        assets.open(name).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun appVersion(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // SAF (ACTION_OPEN_DOCUMENT) exists from API 19; before that there is no
    // system picker (and usually no file-manager app), so fall back to the
    // built-in browser. Both paths funnel into handlePicked().
    private fun pickFile(requestCode: Int, mime: String = "*/*") {
        if (Build.VERSION.SDK_INT >= 19) {
            pickTyped(requestCode, mime)
        } else {
            val extensions = when (requestCode) {
                REQ_MIDI           -> arrayOf("mid", "midi")
                REQ_SOUNDFONT      -> arrayOf("sf2", "sf3", "sfz")
                REQ_BG_IMAGE       -> arrayOf("png", "jpg", "jpeg")
                REQ_IMPORT_PROFILE -> arrayOf("json")
                else -> emptyArray()
            }
            showFileBrowser(requestCode, extensions)
        }
    }

    private fun pickTyped(requestCode: Int, mime: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = mime
        startActivityForResult(intent, requestCode)
    }

    // --- Built-in file browser (for API < 19) ---
    // A simple AlertDialog list starting from /sdcard. Folders navigate, files
    // select (as a file:// URI — contentResolver reads those on every API
    // level, so handlePicked() is shared with the SAF path). ".." goes up.

    private var fileBrowserRequestCode = 0
    private var fileBrowserExtensions: Array<String> = emptyArray()

    private fun showFileBrowser(requestCode: Int, extensions: Array<String>) {
        fileBrowserRequestCode = requestCode
        fileBrowserExtensions = extensions
        showFileBrowserDialog(Environment.getExternalStorageDirectory().absolutePath)
    }

    private fun showFileBrowserDialog(dirPath: String) {
        val dir = File(dirPath)
        val entries = dir.listFiles() ?: arrayOf<File>()
        val folders = entries.filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name }
        val files = entries.filter { it.isFile && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .filter { f ->
                val ext = f.name.substringAfterLast('.', "").lowercase()
                fileBrowserExtensions.isEmpty() || fileBrowserExtensions.any { ext == it }
            }

        val items = ArrayList<String>()
        val itemFiles = ArrayList<File>()
        dir.parent?.let { items.add("[..]"); itemFiles.add(File(it)) }
        for (f in folders) { items.add("[" + f.name + "]"); itemFiles.add(f) }
        for (f in files)   { items.add(f.name);             itemFiles.add(f) }
        if (items.isEmpty()) { items.add("(empty)"); itemFiles.add(dir) }

        val listView = ListView(this)
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)

        val dialog = AlertDialog.Builder(this)
            .setTitle(dir.absolutePath)
            .setView(listView)
            .setNegativeButton("Cancel", null)
            .show()

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val selected = itemFiles[position]
            dialog.dismiss()
            if (selected.isDirectory) {
                showFileBrowserDialog(selected.absolutePath)
            } else {
                handlePicked(fileBrowserRequestCode, Uri.fromFile(selected))
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        handlePicked(requestCode, uri)
    }

    private fun handlePicked(requestCode: Int, uri: Uri) {
        val name = displayName(uri).lowercase()
        when (requestCode) {
            REQ_MIDI -> {
                if (!name.endsWith(".mid") && !name.endsWith(".midi")) {
                    Toast.makeText(this, "Not a MIDI file (.mid)", Toast.LENGTH_SHORT).show()
                    return
                }
                persist(uri)
                midiUri = uri
                launchPlayback()
            }
            REQ_SOUNDFONT -> {
                if (!name.endsWith(".sf2") && !name.endsWith(".sf3") &&
                    !name.endsWith(".sfz")) {
                    Toast.makeText(this, "Not a soundfont (.sf2/.sf3/.sfz)",
                                   Toast.LENGTH_SHORT).show()
                    return
                }
                persist(uri)
                soundfontUri = uri
                soundfontButton.text = "SF: " + displayName(uri)
                saveSettings()        // remember it across launches
            }
            REQ_BG_IMAGE -> {
                if (!name.endsWith(".png") && !name.endsWith(".jpg") && !name.endsWith(".jpeg")) {
                    toast("Pick a .png or .jpg image")
                    return
                }
                // Copy into app storage so it survives the picker's transient grant.
                // BitmapFactory detects the format from content, so a fixed name is fine.
                val path = copyUriToFile(uri, "bg_image")
                if (path == null) { toast("Could not read that image"); return }
                bgImagePath = path
                saveSettings()
                toast("Background image set")
            }
            REQ_EXPORT_PROFILE -> {
                val json = pendingExport ?: return
                pendingExport = null
                try {
                    contentResolver.openOutputStream(uri)!!.use {
                        it.write(json.toString(2).toByteArray())
                    }
                    toast("Exported \"${json.optString("name")}\"")
                } catch (e: Exception) {
                    toast("Export failed")
                }
            }
            REQ_IMPORT_PROFILE -> {
                try {
                    val text = contentResolver.openInputStream(uri)!!.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                    val o = JSONObject(text)
                    val nm = o.optString("name").ifEmpty { "Imported" }
                    saveProfile(nm, o.getInt("r"), o.getInt("g"), o.getInt("b"))
                    toast("Imported \"$nm\" — open Load Profile to use it")
                } catch (e: Exception) {
                    toast("Not a valid colour profile file")
                }
            }
        }
    }

    private fun copyUriToFile(uri: Uri, outName: String): String? = try {
        val out = File(filesDir, outName)
        contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output, 1 shl 16) }
        }
        out.absolutePath
    } catch (e: Exception) {
        null
    }

    private fun persist(uri: Uri) {
        // takePersistableUriPermission is API 19+ — calling it below that is a
        // NoSuchMethodError (not an Exception, so the catch won't save us). The
        // browser's file:// URIs need no grant anyway.
        if (Build.VERSION.SDK_INT < 19) return
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // the in-session grant still applies — fine for this launch
        }
    }

    private fun launchPlayback() {
        val midi = midiUri ?: return
        saveSettings()
        val intent = Intent(this, PlaybackActivity::class.java)
        intent.putExtra(PlaybackActivity.EXTRA_MIDI, midi.toString())
        soundfontUri?.let { intent.putExtra(PlaybackActivity.EXTRA_SF, it.toString()) }
        intent.putExtra(PlaybackActivity.EXTRA_VOICES, voiceCount)
        intent.putExtra(PlaybackActivity.EXTRA_SPEED, noteSpeed)
        intent.putExtra(PlaybackActivity.EXTRA_CPU_MASK, cpuMask)
        intent.putExtra(PlaybackActivity.EXTRA_LEGACY, legacyRenderer)
        intent.putExtra(PlaybackActivity.EXTRA_STREAM, chunkedStreaming)
        intent.putExtra(PlaybackActivity.EXTRA_BG_COLOR, bgColor)
        bgImagePath?.let { intent.putExtra(PlaybackActivity.EXTRA_BG_IMAGE, it) }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(intent)
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "file"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
        } catch (e: Exception) {
            // keep the path-segment fallback
        }
        return name
    }
}
