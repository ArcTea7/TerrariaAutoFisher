package com.example.terrariaautofisher

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Switch
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var openWhenReady = false
    private lateinit var start: Button
    private lateinit var stop: Button
    private val preferences by lazy {getSharedPreferences("fishing_controls",MODE_PRIVATE)}
    private var titleTaps=0
    private var lastTitleTap=0L
    private lateinit var developerSwitch: Switch

    private val refresh = object : Runnable {
        override fun run() {
            val service = FishingAccessibilityService.instance
            if (openWhenReady && service != null) {
                openWhenReady = false
                service.showControls()
            }
            val showing = service?.controlsVisible == true
            start.text = if(showing) "Controls open" else if(service==null) "Enable controls" else "Open controls"
            start.isEnabled = !showing
            stop.isEnabled = showing
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        openWhenReady = savedInstanceState?.getBoolean("openWhenReady") ?: false
        val root = findViewById<View>(R.id.main)
        val padding = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(padding + bars.left, padding + bars.top,
                padding + bars.right, padding + bars.bottom)
            insets
        }
        start = findViewById(R.id.startFloatingButton)
        stop = findViewById(R.id.stopFloatingButton)
        developerSwitch=findViewById(R.id.developerModeSwitch)
        developerSwitch.isChecked=preferences.getBoolean("developerMode",false)
        developerSwitch.visibility=if(developerSwitch.isChecked)View.VISIBLE else View.GONE
        developerSwitch.setOnCheckedChangeListener {_,enabled ->
            preferences.edit().putBoolean("developerMode",enabled).apply()
            if(!enabled)developerSwitch.visibility=View.GONE
            FishingAccessibilityService.instance?.onDeveloperModeChanged()
        }
        findViewById<TextView>(R.id.appTitle).setOnClickListener {
            val now=SystemClock.elapsedRealtime()
            titleTaps=if(now-lastTitleTap>3000)1 else titleTaps+1
            lastTitleTap=now
            if(titleTaps>=5){
                titleTaps=0
                developerSwitch.visibility=View.VISIBLE
                developerSwitch.isChecked=true
                Toast.makeText(this,"Developer mode enabled",Toast.LENGTH_SHORT).show()
            }
        }
        start.setOnClickListener {
            val service = FishingAccessibilityService.instance
            if (service != null) {
                service.showControls()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Enable Terraria Auto Fisher")
                    .setMessage("Allow Terraria Auto Fisher to tap the fishing button. Enable it in Accessibility settings, then return here.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings") { _, _ ->
                        openWhenReady = true
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .show()
            }
        }
        stop.setOnClickListener { FishingAccessibilityService.instance?.closeControls() }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("openWhenReady", openWhenReady)
        super.onSaveInstanceState(outState)
    }
}
