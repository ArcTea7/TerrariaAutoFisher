package com.example.terrariaautofisher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/** Floating setup/inspection and short accessibility taps at the user's fishing marker. */
class FishingAccessibilityService : AccessibilityService() {
    companion object {
        var instance: FishingAccessibilityService? = null
            private set
    }

    var controlsVisible = false
        private set
    private var editing = true
    private var expanded = false
    private var captureRequested = false
    private var captureRequestId = 0L
    private var detectionMessage = "Center the small square on the resting bobber"
    private var lastObservationMessage = "Not started"
    private var detectorPhase = "PAUSED"
    private var detectedBites = 0
    private var snapshotSide = BobberDetector.PATCH
    private val snapshotSizes = intArrayOf(16, 24, 32, 48, 64)
    private lateinit var snapshotLabel: TextView
    private var snapshotPreview: Overlay? = null
    private var snapshotEditor: BobberSnapshotView? = null
    private var inspectEnabled = false
    private lateinit var inspectButton: Button
    private var trackingOverlay: Overlay? = null
    private var trackingPreview: TrackingPreview? = null
    private var trackingStatus: TextView? = null
    private val preferences by lazy { getSharedPreferences("fishing_controls", MODE_PRIVATE) }
    private val manager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private data class Overlay(val view: View, val params: WindowManager.LayoutParams)
    private val attached = mutableListOf<Overlay>()
    private lateinit var marker: Overlay
    private lateinit var region: Overlay
    private lateinit var panel: Overlay
    private lateinit var panelContent: LinearLayout
    private lateinit var panelScroll: ScrollView
    private var panelBounds=Rect()
    private lateinit var menuButton: TextView
    private var panelFullWidth = 0
    private var panelFullHeight = 0
    private var menuSize = 0
    private var menuWidth = 0
    private lateinit var modeLabel: TextView
    private lateinit var editButton: Button
    private lateinit var playButton: Button
    private var lineMode = false
    private var lineAreaMode = true
    private var lineAreaMargin = 16
    private lateinit var lineModeButton: Button
    private val lineSensitivity = .20 // Previous default; removing its control does not tune detection.
    private lateinit var developerRow: View
    private lateinit var cropControls: View
    private var inspectionDetails: TextView? = null
    private var activeWatch: Rect? = null
    private var lastConfidence = 0f
    private var lastReason = ""
    private var lastSearch = ""
    private var lastAnalysisMs = 0f
    private var automationStatus = ""
    private var viewObscured = false
    private var publishedWindows = IntArray(0)
    private val gestureHandler = Handler(Looper.getMainLooper())
    private var gestureLease = 0L
    private var gestureFlags: List<Pair<Overlay,Int>> = emptyList()
    private val developerMode: Boolean get() = preferences.getBoolean("developerMode",false)
    private var lineEditor: LineSetupView? = null
    private var screen = Rect()
    private var geometryKey = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        closeControls()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        closeControls()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        closeControls()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val bounds = manager.currentWindowMetrics.bounds
        if (controlsVisible && (bounds.width() != screen.width() || bounds.height() != screen.height())) {
            // Save under the old screen dimensions before loading the new layout.
            closeControls()
            showControls()
            toast("Screen changed. Check the fishing point and bobber area.")
        }
    }

    fun showControls() {
        if (controlsVisible) return
        val metrics=manager.currentWindowMetrics
        screen = metrics.bounds
        val safe=metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        panelBounds=Rect(safe.left+dp(8),safe.top+dp(8),
            screen.width()-safe.right-dp(8),screen.height()-safe.bottom-dp(8))
        if(panelBounds.width()<1||panelBounds.height()<1)
            panelBounds=Rect(0,0,screen.width(),screen.height())
        geometryKey = "${screen.width()}x${screen.height()}"
        editing = true
        expanded = false
        snapshotSide = preferences.getInt("snapshotSide", BobberDetector.PATCH).coerceIn(16, 64)
        inspectEnabled = preferences.getBoolean("inspectEnabled", false)
        lineAreaMargin = preferences.getInt("lineAreaMargin",16).coerceIn(8,48)
        val width = screen.width()
        val height = screen.height()
        val size = dp(48).coerceAtMost(minOf(width, height))
        val maxBoxWidth = BobberDetector.maxSourceWidth(width, height)
        val maxBoxHeight = BobberDetector.maxSourceHeight(width, height)
        val minBox = minOf(dp(80), maxBoxWidth, maxBoxHeight)
        val boxWidth = saved("boxW", dp(220)).coerceIn(minBox, maxBoxWidth)
        val boxHeight = saved("boxH", dp(150)).coerceIn(minBox, maxBoxHeight)
        region = Overlay(WatchArea(), params(boxWidth, boxHeight,
            saved("boxX", width / 3), saved("boxY", height / 3)))
        marker = Overlay(TapMarker(), params(size, size,
            saved("tapX", width * 4 / 5), saved("tapY", height * 2 / 3)))

        menuSize = dp(48).coerceAtMost(minOf(panelBounds.width(),panelBounds.height()))
        menuWidth = menuSize
        panelFullWidth = minOf(dp(270), panelBounds.width())
        panelContent = makePanel()
        panelContent.measure(View.MeasureSpec.makeMeasureSpec(panelFullWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panelFullHeight = panelContent.measuredHeight.coerceAtMost(panelBounds.height())
        menuButton = label("☰", 25f).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP,25f)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(40,30,4))
            includeFontPadding = false
            maxLines = 1
            background = GradientDrawable().apply {
                setColor(Color.rgb(245,202,65))
                cornerRadius=dp(12).toFloat()
                setStroke(dp(1),Color.rgb(132,236,162))
            }
            contentDescription = "Fishing menu. Tap to open; drag to move."
            setOnClickListener { setExpanded(true) }
        }
        panelScroll=ScrollView(this).apply {
            isFillViewport=false
            addView(panelContent)
        }
        val container = FrameLayout(this).apply {
            addView(panelScroll, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(menuButton, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        panelScroll.visibility = View.GONE
        marker.view.visibility = View.GONE
        region.view.visibility = View.GONE
        panel = Overlay(container, params(menuWidth, menuSize,
            saved("panelX", dp(12)), saved("panelY", dp(64))))
        makeDraggable(marker.view, marker)
        makeRegionDraggable()
        makeDraggable(menuButton, panel)
        val header = panelContent.getChildAt(0)
        header.setOnClickListener { setExpanded(false) }
        makeDraggable(header, panel)

        try {
            // Separate small windows leave the rest of Terraria touchable.
            listOf(region, marker, panel).forEach { overlay ->
                clamp(overlay)
                manager.addView(overlay.view, overlay.params)
                attached.add(overlay)
            }
            controlsVisible = true
            setEditing(true)
            updateMenuBadge();publishFloatingWindows()
        } catch (error: RuntimeException) {
            closeControls()
            toast("Could not open controls. Re-enable the accessibility service and try again.")
        }
    }

    fun closeControls() {
        ScreenCaptureService.instance?.pauseMonitoring("Stopped.")
        restoreFishingInput()
        if (controlsVisible) savePositions()
        controlsVisible = false
        publishFloatingWindows()
        expanded = false
        captureRequested = false
        captureRequestId = 0L
        stopService(Intent(this, ScreenCaptureService::class.java))
        hideTrackingPreview()
        attached.asReversed().forEach { overlay ->
            runCatching { manager.removeViewImmediate(overlay.view) }
        }
        attached.clear()
        snapshotEditor?.release(); snapshotEditor = null
        lineEditor?.release(); lineEditor = null
        snapshotPreview = null
    }

    private fun setExpanded(value: Boolean) {
        if (!controlsVisible) return
        val nextWidth=if(value)panelFullWidth else menuWidth
        val nextHeight=if(value)panelFullHeight else menuSize
        expanded=value
        panelScroll.visibility=if(value)View.VISIBLE else View.GONE
        menuButton.visibility=if(value)View.GONE else View.VISIBLE
        panel.params.width=nextWidth;panel.params.height=nextHeight
        clamp(panel);manager.updateViewLayout(panel.view,panel.params)
        applyEditingWindows()
        publishFloatingWindows()
    }

    private fun playOrPause() = startAction(false)

    private fun startAction(takeSnapshot: Boolean) {
        if (captureRequested || snapshotPreview != null) return
        val capture = ScreenCaptureService.instance
        if (capture?.monitoring == true) {
            capture.pauseMonitoring()
            if (!takeSnapshot) return
        }
        val bounds = if (lineMode) {
            (if (!takeSnapshot) capture?.lineBoundsOnScreen(if(lineAreaMode) lineAreaMargin+12 else 12) else null)
                ?: Rect(screen.centerX()-64,screen.centerY()-64,screen.centerX()+64,screen.centerY()+64)
        } else watchBounds()
        if (bounds == null || bounds.width() < 32 || bounds.height() < 32) {
            toast("Open the controls and place the box around the bobber first.")
            return
        }
        if(!takeSnapshot&&fishingTapTarget()==null){
            setEditing(true)
            toast("Drag the fishing marker onto Terraria's fishing button, then Lock and Start.")
            return
        }
        if(!takeSnapshot&&fishingTapTarget()?.let {bounds.contains(it.x,it.y)}==true){
            setEditing(true)
            toast("Place the fishing marker on the fishing button, outside the detection area.")
            return
        }
        activeWatch=Rect(bounds)
        viewObscured=false
        setEditing(false)
        setExpanded(false)
        if (capture?.hasSession == true) {
            capture.enableDiagnostics(inspectEnabled)
            if (takeSnapshot) {
                hideTrackingPreview()
                capture.captureSnapshot(bounds, effectiveSnapshotSide(), lineMode)
            } else {
                if (inspectEnabled) {
                    if(lineMode) showLineInspection(bounds) else showTrackingPreview(bounds)
                }
                capture.beginMonitoring(bounds, lineMode, lineSensitivity, true, lineAreaMargin)
            }
            return
        }
        captureRequested = true
        captureRequestId = SystemClock.elapsedRealtimeNanos()
        detectorPhase = "STARTING"
        detectionMessage = "Approve screen sharing, then tap Capture or Start."
        playButton.isEnabled = false
        // Keep all our overlays off Android's permission dialog.
        panel.view.visibility = View.GONE
        publishFloatingWindows()
        try {
            startActivity(Intent(this, CapturePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(CapturePermissionActivity.AREA, bounds)
                .putExtra(CapturePermissionActivity.WIDTH, screen.width())
                .putExtra(CapturePermissionActivity.HEIGHT, screen.height())
                .putExtra(CapturePermissionActivity.REQUEST, captureRequestId))
        } catch (error: RuntimeException) {
            captureRequestFailed(captureRequestId, "Could not open screen approval. Tap Start to retry.")
        }
    }

    fun acceptsCaptureRequest(request: Long): Boolean =
        controlsVisible && captureRequested && request == captureRequestId

    fun captureRequestFailed(request: Long, message: String) {
        if (!acceptsCaptureRequest(request)) return
        detectionUpdate("PAUSED", message, 0f, detectedBites, false)
        toast(message)
    }

    fun detectionUpdate(phase: String, message: String, confidence: Float, count: Int, bite: Boolean) {
        if (!controlsVisible) return
        captureRequested = false
        captureRequestId = 0L
        detectorPhase = phase
        if(phase=="READY"){publishedWindows=IntArray(0);publishFloatingWindows()}
        detectedBites = count
        if(phase!="PAUSED")lastConfidence = confidence
        if (phase != "PAUSED") lastObservationMessage = message
        detectionMessage = if(phase in setOf("READY","PAUSED","STOPPED","NEEDS_REFERENCE"))
            DetectionUi.message(lineMode,phase,message)
            else DetectionUi.status(lineMode,phase,lastReason)
        refreshInspectionStatus()
        panel.view.visibility = View.VISIBLE
        publishFloatingWindows()
        playButton.isEnabled = true
        playButton.text = DetectionUi.primary(ScreenCaptureService.instance?.monitoring == true,phase == "PAUSED")
        if (!editing) setMenuStatus(detectionMessage)
        updateMenuBadge()
    }

    fun captureSessionEnded() {
        if (!controlsVisible) return
        val message = if (detectorPhase == "STOPPED") detectionMessage
            else "Screen sharing stopped. Tap Start to start again."
        captureRequested = false
        captureRequestId = 0L
        detectorPhase = "STOPPED"
        detectionMessage = message
        playButton.isEnabled = true
        playButton.text = "Start"
        hideTrackingPreview()
        panel.view.visibility = View.VISIBLE
        if (!editing) setMenuStatus(message)
        updateMenuBadge()
    }

    private fun updateMenuBadge() {
        menuButton.text="☰"
        menuButton.contentDescription="Fishing menu. Tap to open; drag to move."
    }

    /** Absolute screen coordinates for the next stage's tap and image detector. */
    fun tapPoint(): Point? {
        if (!controlsVisible || !expanded) return null
        return Point(marker.params.x+marker.params.width/2,marker.params.y+marker.params.height/2)
    }

    /** Marker placement is explicit; coordinates are valid even when controls are collapsed. */
    fun fishingTapTarget(): Point? {
        if(!controlsVisible||!preferences.getBoolean("${geometryKey}_tapPlaced",false))return null
        return Point(marker.params.x+marker.params.width/2,marker.params.y+marker.params.height/2)
            .takeIf {screen.contains(it.x,it.y)}
    }

    fun automationUpdate(message:String){
        automationStatus=message
        refreshInspectionStatus()
        if(controlsVisible&&!editing)setMenuStatus(if(message.isNotEmpty())message else detectionMessage)
    }

    /** Restore after completion, timeout, Pause or Stop. A stale gesture cannot restore newer flags. */
    fun restoreFishingInput(){
        gestureLease++
        val flags=gestureFlags;gestureFlags=emptyList()
        flags.forEach {(overlay,original) ->
            if(attached.contains(overlay)){
                overlay.params.flags=original
                runCatching {manager.updateViewLayout(overlay.view,overlay.params)}
            }
        }
    }

    fun dispatchFishingTap(target:Point,completion:FishingLoop.Completion){
        if(!controlsVisible||snapshotPreview!=null||ScreenCaptureService.instance?.monitoring!=true||
            !screen.contains(target.x,target.y)){
            completion.finish(false);return
        }
        restoreFishingInput()
        val lease=gestureLease
        // Let the injected touch reach Terraria even if a floating panel covers the button.
        gestureFlags=attached.filter {
            it.view.visibility==View.VISIBLE&&overlayRect(it).contains(target.x,target.y)&&
                (it.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0
        }.map {it to it.params.flags}
        gestureFlags.forEach {(overlay,_) ->
            overlay.params.flags=overlay.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching {manager.updateViewLayout(overlay.view,overlay.params)}
        }
        var done=false
        fun finish(success:Boolean){
            if(done)return
            done=true
            if(lease==gestureLease)restoreFishingInput()
            completion.finish(success)
        }
        // Keep our controls usable even if Android never calls the gesture callback.
        gestureHandler.postDelayed({if(lease==gestureLease)restoreFishingInput()},100)
        val path=Path().apply {moveTo(target.x.toFloat(),target.y.toFloat())}
        val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,40)).build()
        try {
            val accepted=dispatchGesture(gesture,object:GestureResultCallback(){
                override fun onCompleted(gestureDescription:GestureDescription){finish(true)}
                override fun onCancelled(gestureDescription:GestureDescription){finish(false)}
            },gestureHandler)
            if(!accepted)finish(false)
        }catch(error:RuntimeException){finish(false)}
    }

    fun watchBounds(): Rect? {
        if (!controlsVisible || !expanded) return null
        return overlayRect(region)
    }

    private fun lineModeName() = if(lineMode) "Mode: Line" else "Mode: Bobber"

    private fun makePanel(): LinearLayout {
        val narrow=panelFullWidth<dp(244)*resources.configuration.fontScale
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.rgb(21, 35, 28))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(92, 135, 106))
            }
            addView(LinearLayout(this@FishingAccessibilityService).apply {
                orientation=LinearLayout.VERTICAL
                gravity=Gravity.CENTER
                setPadding(0,dp(2),0,dp(2))
                contentDescription="Auto Fisher. Tap to hide menu; drag to move."
                addView(label("Auto Fisher",13f).apply {gravity=Gravity.CENTER;includeFontPadding=false})
                addView(label("Tap to hide",10f).apply {
                    gravity=Gravity.CENTER;includeFontPadding=false
                    setTextColor(Color.rgb(170,187,176))
                })
            })
            modeLabel = label("Edit positions", 12f).apply {
                gravity = Gravity.CENTER
                minLines=1;maxLines=2
                includeFontPadding=false
                ellipsize=TextUtils.TruncateAt.END
            }
            addView(modeLabel)
            editButton=compactButton("Lock") {setEditing(!editing)}
            playButton=compactButton("Start") {playOrPause()}
            val editCapture=compactButton("Edit capture") {
                if(ScreenCaptureService.instance?.reopenSnapshot(lineMode)!=true)toast("Take a capture first.")
            }
            val exit=compactButton("Exit") {closeControls()}
            listOf(editButton,playButton,editCapture,exit).chunked(if(narrow)2 else 4).forEach { buttons ->
                addView(LinearLayout(this@FishingAccessibilityService).apply {
                    orientation=LinearLayout.HORIZONTAL
                    buttons.forEach {b ->addView(b,menuCell(if(!narrow&&b===editCapture)1.5f else 1f))}
                })
            }
            val capture=compactButton("Capture") {startAction(true)}
            cropControls=LinearLayout(this@FishingAccessibilityService).apply {
                orientation=LinearLayout.HORIZONTAL
                addView(compactButton("−") {changeSnapshotSize(-1)},menuCell())
                snapshotLabel=label("Crop: ${effectiveSnapshotSide()}",11f).apply {
                    gravity=Gravity.CENTER;includeFontPadding=false;maxLines=2
                }
                addView(snapshotLabel,menuCell())
                addView(compactButton("+") {changeSnapshotSize(1)},menuCell())
            }
            cropControls.visibility=if(lineMode)View.GONE else View.VISIBLE
            if(narrow){
                addView(capture,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,menuRowHeight()))
                addView(cropControls,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,menuRowHeight()))
            }else addView(LinearLayout(this@FishingAccessibilityService).apply {
                orientation=LinearLayout.HORIZONTAL
                addView(capture,menuCell());addView(cropControls,menuCell(3f))
            })
            addView(LinearLayout(this@FishingAccessibilityService).apply {
                orientation=LinearLayout.HORIZONTAL
                lineModeButton=compactButton(lineModeName()) {
                    ScreenCaptureService.instance?.pauseMonitoring("Mode changed. Tap Start when ready.")
                    lineMode=!lineMode;lineAreaMode=true;lastReason=""
                    lineModeButton.text=lineModeName()
                    hideTrackingPreview();activeWatch=null
                    cropControls.visibility=if(lineMode)View.GONE else View.VISIBLE
                    setEditing(editing);resizePanel()
                }
                addView(lineModeButton,menuCell())
                inspectButton=compactButton(if(inspectEnabled)"Inspect on" else "Inspect off") {setInspectEnabled(!inspectEnabled)}
                addView(inspectButton,menuCell())
            })
            developerRow=LinearLayout(this@FishingAccessibilityService).apply {
                orientation=LinearLayout.HORIZONTAL
                addView(compactButton("Copy log") {ScreenCaptureService.instance?.copyDiagnostics()},menuCell())
                visibility=if(developerMode)View.VISIBLE else View.GONE
            }
            addView(developerRow)
        }
    }

    private fun resizePanel(){
        panelContent.measure(View.MeasureSpec.makeMeasureSpec(panelFullWidth,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val nextHeight=panelContent.measuredHeight.coerceAtMost(panelBounds.height())
        if(nextHeight!=panelFullHeight){
            panelFullHeight=nextHeight
            if(controlsVisible)setExpanded(expanded)
        }
    }

    private fun setMenuStatus(text:String){
        if(modeLabel.text.toString()==text)return
        modeLabel.text=text
        if(controlsVisible&&::panel.isInitialized)resizePanel()
    }

    private fun menuRowHeight():Int {
        val font=modeLabel.paint.fontMetricsInt
        return maxOf(dp(48),2*(font.descent-font.ascent)+dp(8))
    }
    private fun menuCell(weight:Float=1f)=LinearLayout.LayoutParams(0,menuRowHeight(),weight)

    /** Uniform visual insets; the entire cell remains a touch target. */
    private fun compactButton(value:String,action:()->Unit)=button(value,action).apply {
        minHeight=0;minimumHeight=0;includeFontPadding=false
        gravity=Gravity.CENTER;maxLines=2;ellipsize=TextUtils.TruncateAt.END
        setPadding(dp(4),0,dp(4),0)
        val shape=GradientDrawable().apply {
            setColor(Color.rgb(48,64,55));cornerRadius=dp(6).toFloat()
        }
        background=RippleDrawable(ColorStateList.valueOf(Color.argb(60,235,246,239)),
            InsetDrawable(shape,dp(2),dp(3),dp(2),dp(3)),null)
        backgroundTintList=null
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),
            intArrayOf(Color.rgb(126,142,132),Color.rgb(235,246,239))))
    }

    fun onDeveloperModeChanged(){
        if(!controlsVisible)return
        developerRow.visibility=if(developerMode)View.VISIBLE else View.GONE
        resizePanel()
        if(inspectEnabled&&snapshotPreview==null)activeWatch?.let {showInspection(it)}
    }

    private fun setInspectEnabled(enabled: Boolean) {
        inspectEnabled = enabled
        preferences.edit().putBoolean("inspectEnabled", enabled).apply()
        inspectButton.text = if (enabled) "Inspect on" else "Inspect off"
        ScreenCaptureService.instance?.enableDiagnostics(enabled)
        if (!enabled) hideTrackingPreview()
        else if(snapshotPreview==null) activeWatch?.let {showInspection(it)}
    }

    private fun hideTrackingPreview() {
        trackingPreview?.release()
        trackingOverlay?.let { overlay ->
            overlay.view.visibility=View.GONE
            overlay.params.flags=overlay.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching {manager.updateViewLayout(overlay.view,overlay.params)}
            runCatching { manager.removeViewImmediate(overlay.view) }
            attached.remove(overlay)
        }
        trackingOverlay = null
        trackingPreview = null
        trackingStatus = null
        inspectionDetails=null
        publishFloatingWindows()
    }

    private fun showLineInspection(watched: Rect) = showInspection(watched)
    private fun showTrackingPreview(watched: Rect) = showInspection(watched)

    private fun rectArray(r: Rect)=intArrayOf(r.left,r.top,r.right,r.bottom)
    private fun overlayRect(o: Overlay)=Rect(o.params.x,o.params.y,o.params.x+o.params.width,o.params.y+o.params.height)

    private fun showInspection(watched: Rect){
        hideTrackingPreview()
        if(!controlsVisible||!inspectEnabled)return
        activeWatch=Rect(watched)
        val image=TrackingPreview(this)
        val title=label(if(lineMode)"Inspect · Line" else "Inspect · Bobber",12f).apply {
            setPadding(dp(4),dp(8),dp(4),dp(8))
            contentDescription="Inspect. Drag this title to move the window."
        }
        val status=label("",12f).apply {minLines=3;maxLines=3}
        val details=label("",10f).apply {minLines=2;maxLines=3;visibility=if(developerMode)View.VISIBLE else View.GONE}
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(4),dp(6),dp(4))
            background=GradientDrawable().apply {setColor(Color.rgb(21,35,28));cornerRadius=dp(8).toFloat()}
            addView(title)
            addView(image,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(105)))
            addView(status);addView(details)
            addView(LinearLayout(this@FishingAccessibilityService).apply {
                orientation=LinearLayout.HORIZONTAL
                if(developerMode){
                    addView(button("Mark miss") {ScreenCaptureService.instance?.markMissedBite()},rowCell())
                    addView(button("Copy log") {ScreenCaptureService.instance?.copyDiagnostics()},rowCell())
                }
                addView(button("Hide") {setInspectEnabled(false)},rowCell())
            })
        }
        val width=minOf(dp(230),(screen.width()-dp(16)).coerceAtLeast(1))
        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val height=content.measuredHeight.coerceAtMost((screen.height()-dp(16)).coerceAtLeast(1))
        val position=OverlayPlacement.openCorner(screen.width(),screen.height(),width,height,
            panel.params.x,menuWidth,dp(8))
        val scroll=ScrollView(this).apply {addView(content)}
        val overlay=Overlay(scroll,params(width,height,position[0],position[1]))
        makeDraggable(title,overlay)
        try {
            manager.addView(scroll,overlay.params);attached.add(overlay)
            trackingOverlay=overlay;trackingPreview=image;trackingStatus=status;inspectionDetails=details
            refreshInspectionStatus();publishFloatingWindows()
        }catch(error:RuntimeException){image.release();toast("Could not open Inspect.")}
    }

    private fun refreshInspectionStatus(){
        val status=automationStatus.ifEmpty {
            if(viewObscured)if(lineMode)"Line obscured" else "Bobber obscured"
            else DetectionUi.status(lineMode,detectorPhase,lastReason)
        }
        trackingStatus?.text="$status\n"+
            "${DetectionUi.metric(lineMode,lastConfidence)}\n${DetectionUi.count(detectedBites)}"
        inspectionDetails?.text=if(developerMode) "${lastReason.lowercase().replace('_',' ')}\n"+
            "$lastSearch · ${lastAnalysisMs.roundToInt()} ms processing" else ""
    }

    /** Layout is free. Only windows that currently exist and are visible affect screen input. */
    private fun publishFloatingWindows(){
        val windows=if(controlsVisible)listOfNotNull(if(::panel.isInitialized)panel else null,trackingOverlay)
            .filter {it.view.visibility==View.VISIBLE} else emptyList()
        val bounds=windows.flatMap {rectArray(overlayRect(it)).toList()}.toIntArray()
        val watched=activeWatch
        val affects=watched!=null&&(OverlayOcclusion.intersects(bounds,watched.left,watched.top,watched.right,watched.bottom)||
            OverlayOcclusion.intersects(publishedWindows,watched.left,watched.top,watched.right,watched.bottom))
        if(!bounds.contentEquals(publishedWindows)){
            publishedWindows=bounds
            ScreenCaptureService.instance?.floatingWindowsChanged(bounds,affects)
        }
    }

    fun obstructionUpdate(obscured:Boolean){
        viewObscured=obscured;refreshInspectionStatus()
    }

    fun updateTrackingPreview(pixels:IntArray,width:Int,height:Int,sourceWidth:Int,sourceHeight:Int,
                              result:BobberDetector.Result,analysisMs:Float,count:Int){
        if(!controlsVisible||!inspectEnabled||trackingOverlay==null||lineMode)return
        trackingPreview?.update(pixels,width,height,sourceWidth,sourceHeight,result)
        detectorPhase=result.phase;lastReason=result.reason;lastSearch=result.searchSource
        lastAnalysisMs=analysisMs;lastConfidence=result.confidence;detectedBites=count
        refreshInspectionStatus()
    }

    fun updateLinePreview(pixels:IntArray,width:Int,height:Int,path:Array<FloatArray>,
                          result:FishingLineTracker.Result,analysisMs:Float,count:Int){
        if(!controlsVisible||!inspectEnabled||trackingOverlay==null||!lineMode)return
        trackingPreview?.updateLine(pixels,width,height,path)
        detectorPhase=result.phase;lastReason=result.reason;lastSearch="Line area"
        lastAnalysisMs=analysisMs;lastConfidence=result.visible.toFloat();detectedBites=count
        refreshInspectionStatus()
    }

    private fun effectiveSnapshotSide(): Int {
        val plan = runCatching { RoiSampling.forScreen(screen.width(), screen.height(),
            region.params.x, region.params.y, region.params.width, region.params.height) }.getOrNull()
        return (minOf(snapshotSide, (plan?.width ?: 24)-8, (plan?.height ?: 24)-8).coerceAtLeast(16) and -2)
    }

    private fun changeSnapshotSize(direction: Int) {
        val index = snapshotSizes.indices.minByOrNull { abs(snapshotSizes[it] - snapshotSide) } ?: 1
        snapshotSide = snapshotSizes[(index + direction).coerceIn(0, snapshotSizes.lastIndex)]
        preferences.edit().putInt("snapshotSide", snapshotSide).apply()
        setEditing(true)
        snapshotLabel.text = "Crop: ${effectiveSnapshotSide()}"
        region.view.invalidate()
    }

    fun hideForSnapshot() {
        hideTrackingPreview()
        region.view.visibility = View.GONE
        marker.view.visibility = View.GONE
        panel.view.visibility = View.GONE
        publishFloatingWindows()
    }

    fun showSnapshot(snapshot: SnapshotEditorModel) {
        if (!controlsVisible) return
        hideSnapshotPreview()
        hideTrackingPreview()
        setExpanded(false)
        panel.view.visibility = View.GONE
        val editor = BobberSnapshotView(this, snapshot).apply {moving=true}
        var saving = false
        val header = label("Edit capture", 13f)
        val hint = label("Center the bobber in the square.", 12f)
        val cropLabel = label("Crop: ${snapshot.side}", 11f).apply { gravity = Gravity.CENTER }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.rgb(21, 35, 28))
            addView(header)
            addView(hint)
            val editorHeight = minOf(dp(200), (screen.height()-dp(240)).coerceAtLeast(dp(64)))
            addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, editorHeight))
            addView(LinearLayout(this@FishingAccessibilityService).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("Move") {
                    if (!saving) {
                        editor.moving = true
                        hint.text = "Center the bobber in the square."
                    }
                }, rowCell())
                addView(button("Erase") {
                    if (!saving) {
                        editor.overview = false; editor.moving = false; editor.erase = true
                        hint.text = "Erase the background."
                    }
                }, rowCell())
                addView(button("Restore") {
                    if (!saving) {
                        editor.overview = false; editor.moving = false; editor.erase = false
                        hint.text = "Restore erased areas."
                    }
                }, rowCell())
                addView(button("Reset") { if (!saving) editor.resetMask() }, rowCell())
            })
            addView(LinearLayout(this@FishingAccessibilityService).apply {
                orientation = LinearLayout.HORIZONTAL
                fun resize(direction: Int) {
                    if (saving) return
                    val index = snapshotSizes.indices.minByOrNull { abs(snapshotSizes[it]-snapshot.side) } ?: 1
                    snapshot.setSide(snapshotSizes[(index+direction).coerceIn(0,snapshotSizes.lastIndex)])
                    cropLabel.text = "Crop: ${snapshot.side}"; editor.refresh()
                }
                addView(button("−") { resize(-1) }, rowCell())
                addView(cropLabel, rowCell())
                addView(button("+") { resize(1) }, rowCell())
                addView(button("Overview") {
                    if (!saving) {
                        editor.moving = true; editor.overview = !editor.overview
                        hint.text = "Center the bobber in the square."
                    }
                }, rowCell())
            })
        }
        val holder = ScrollView(this).apply { addView(content) }
        val preview = Overlay(holder, params(minOf(dp(340), screen.width()-dp(16)),
            WindowManager.LayoutParams.WRAP_CONTENT, 0, 0))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Save") {
                if (saving) return@button
                val approved = try { editor.reference() } catch (error: IllegalArgumentException) {
                    toast("Keep more of the bobber, or retake the capture.")
                    return@button
                }
                val capture = ScreenCaptureService.instance
                if (capture == null || !capture.hasSession) {
                    toast("Screen sharing stopped. Start it again.")
                    return@button
                }
                val savedSide = snapshot.side
                val boxWidth = maxOf(region.params.width,
                    Math.ceil((savedSide+8.0)*screen.width()/snapshot.width).toInt())
                    .coerceAtMost(BobberDetector.maxSourceWidth(screen.width(),screen.height()))
                val boxHeight = maxOf(region.params.height,
                    Math.ceil((savedSide+8.0)*screen.height()/snapshot.height).toInt())
                    .coerceAtMost(BobberDetector.maxSourceHeight(screen.width(),screen.height()))
                val position = try { snapshot.watchPosition(screen.width(),screen.height(),boxWidth,boxHeight) }
                    catch (error: IllegalArgumentException) {
                        toast("Enlarge the box to fit the selection.")
                        return@button
                    }
                saving = true; editor.isEnabled = false
                capture.saveReference(approved) { saved ->
                    if (!controlsVisible || snapshotPreview !== preview) return@saveReference
                    if (saved) {
                        region.params.x = position[0]; region.params.y = position[1]
                        region.params.width = boxWidth; region.params.height = boxHeight
                        manager.updateViewLayout(region.view,region.params)
                        snapshotSide = savedSide
                        preferences.edit().putInt("snapshotSide",savedSide).apply()
                        snapshotLabel.text = "Crop: ${effectiveSnapshotSide()}"
                        savePositions()
                        hideSnapshotPreview()
                        detectionUpdate("READY", "Capture saved.", 0f, detectedBites, false)
                        setExpanded(true)
                    } else {
                        saving = false; editor.isEnabled = true
                        toast("Could not save the capture. Try again.")
                    }
                }
            }, rowCell())
            addView(button("Retake") {
                if (!saving) {
                    hideSnapshotPreview(); setExpanded(true)
                    toast("Let the bobber rest, then tap Capture")
                }
            }, rowCell())
            addView(button("Cancel") {
                if (!saving) { hideSnapshotPreview(); setExpanded(true) }
            }, rowCell())
        })
        holder.measure(View.MeasureSpec.makeMeasureSpec(preview.params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen.height()-dp(16), View.MeasureSpec.AT_MOST))
        preview.params.height = holder.measuredHeight
        preview.params.x = (screen.width()-preview.params.width)/2
        preview.params.y = (screen.height()-preview.params.height)/2
        clamp(preview)
        makeDraggable(header,preview)
        try {
            manager.addView(holder, preview.params)
            attached.add(preview)
            snapshotPreview = preview
            snapshotEditor = editor
        } catch (error: RuntimeException) {
            editor.release()
            panel.view.visibility = View.VISIBLE
            toast("Could not open the capture. Try again.")
        }
    }

    fun showLineSnapshot(snapshot: SnapshotEditorModel) {
        if (!controlsVisible) return
        hideSnapshotPreview(); hideTrackingPreview(); setExpanded(false)
        if (!snapshot.hasColor()) { toast("Take a new Capture in Line mode"); return }
        panel.view.visibility=View.GONE
        val editor=LineSetupView(this,snapshot).apply {
            selecting=false
            showArea=lineAreaMode;areaMargin=lineAreaMargin
        }
        var saving=false
        val header=FrameLayout(this).apply {
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(24))
            contentDescription="Move capture window"
            addView(View(this@FishingAccessibilityService).apply {
                background=GradientDrawable().apply {setColor(Color.rgb(150,170,158));cornerRadius=dp(2).toFloat()}
            },FrameLayout.LayoutParams(dp(36),dp(4),Gravity.CENTER))
        }
        val hint=label("",12f).apply {visibility=View.GONE}
        editor.onSelection={ hint.text=it;hint.visibility=View.VISIBLE }
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8))
            setBackgroundColor(Color.rgb(21,35,28));addView(header);addView(hint)
            if(lineAreaMode){
                addView(LinearLayout(this@FishingAccessibilityService).apply {
                    orientation=LinearLayout.HORIZONTAL
                    addView(label("Search area",11f),LinearLayout.LayoutParams(dp(85),dp(40)))
                    addView(SeekBar(this@FishingAccessibilityService).apply {
                        max=10;progress=(lineAreaMargin-8)/4
                        setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean){editor.areaMargin=8+4*value}
                            override fun onStartTrackingTouch(bar:SeekBar?){}
                            override fun onStopTrackingTouch(bar:SeekBar?){}
                        })
                    },LinearLayout.LayoutParams(0,dp(40),1f))
                })
            }
            val editorHeight=minOf(dp(220),(screen.height()-dp(if(lineAreaMode)225 else 185)).coerceAtLeast(dp(64)))
            addView(editor,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,editorHeight))
            addView(LinearLayout(this@FishingAccessibilityService).apply {
                orientation=LinearLayout.HORIZONTAL
                val pan=button("Pan") {}
                val select=button("Select line") {}
                fun choose(selecting:Boolean){
                    editor.selecting=selecting
                    hint.visibility=if(selecting)View.VISIBLE else View.GONE
                    if(selecting)hint.text="Tap the fishing line."
                    listOf(pan,select).forEach {b ->
                        val active=if(b===select)selecting else !selecting
                        b.isSelected=active
                        b.backgroundTintList=ColorStateList.valueOf(if(active)Color.rgb(76,100,84) else Color.rgb(42,56,47))
                        b.setTextColor(Color.rgb(235,246,239))
                    }
                }
                pan.setOnClickListener {choose(false)}
                select.setOnClickListener {choose(true)}
                addView(pan,rowCell());addView(select,rowCell())
                choose(false)
            })
        }
        val holder=ScrollView(this).apply {addView(content)}
        val preview=Overlay(holder,params(minOf(dp(420),screen.width()-dp(16)),WindowManager.LayoutParams.WRAP_CONTENT,0,0))
        content.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            addView(button("Save") {
                if(saving)return@button
                val selected=editor.selection
                if(selected==null){toast("Select the fishing line first.");return@button}
                val capture=ScreenCaptureService.instance
                if(capture==null||!capture.hasSession){toast("Screen sharing stopped. Start it again.");return@button}
                saving=true;editor.isEnabled=false
                capture.saveLine(selected) {saved ->
                    if(!controlsVisible||snapshotPreview!==preview)return@saveLine
                    if(saved){
                        lineAreaMargin=editor.areaMargin
                        preferences.edit().putInt("lineAreaMargin",lineAreaMargin).apply()
                        hideSnapshotPreview()
                        detectionUpdate("READY","Capture saved.",0f,detectedBites,false)
                        setExpanded(true)
                    } else {saving=false;editor.isEnabled=true;toast("Could not save line. Try again")}
                }
            },rowCell())
            addView(button("Retake") {if(!saving){hideSnapshotPreview();setExpanded(true);startAction(true)}},rowCell())
            addView(button("Cancel") {if(!saving){hideSnapshotPreview();setExpanded(true)}},rowCell())
        })
        holder.measure(View.MeasureSpec.makeMeasureSpec(preview.params.width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen.height()-dp(16),View.MeasureSpec.AT_MOST))
        preview.params.height=minOf(holder.measuredHeight,screen.height()-dp(16))
        preview.params.x=(screen.width()-preview.params.width)/2;preview.params.y=(screen.height()-preview.params.height)/2
        makeDraggable(header,preview)
        try {
            manager.addView(holder,preview.params);attached.add(preview);snapshotPreview=preview;lineEditor=editor
        } catch(error:RuntimeException){editor.release();panel.view.visibility=View.VISIBLE;toast("Could not show line setup")}
    }

    private fun hideSnapshotPreview() {
        snapshotEditor?.release(); snapshotEditor = null
        lineEditor?.release(); lineEditor = null
        snapshotPreview?.let { preview ->
            runCatching { manager.removeViewImmediate(preview.view) }
            attached.remove(preview)
        }
        snapshotPreview = null
        if (controlsVisible) panel.view.visibility = View.VISIBLE
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(235, 246, 239))
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { action() }
    }

    private fun rowCell() = LinearLayout.LayoutParams(0, dp(48), 1f)

    private fun setEditing(value: Boolean) {
        if (value && ScreenCaptureService.instance?.monitoring == true) {
            ScreenCaptureService.instance?.pauseMonitoring("Paused for positioning. Tap Resume when ready.")
        }
        editing = value
        editButton.text = if (editing) "Lock" else "Edit"
        setMenuStatus(if (editing) {
            if(lineMode) "Capture, select the line, then Save."
            else "Place the fishing marker; center the square on the bobber."
        } else detectionMessage)
        applyEditingWindows()
        savePositions()
    }

    private fun applyEditingWindows(){
        val positioning=editing&&expanded&&ScreenCaptureService.instance?.monitoring!=true
        marker.view.visibility=if(positioning)View.VISIBLE else View.GONE
        region.view.visibility=if(positioning&&!lineMode)View.VISIBLE else View.GONE
        listOf(marker,region).forEach {overlay ->
            overlay.params.flags=if(positioning)baseFlags else baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            manager.updateViewLayout(overlay.view,overlay.params);overlay.view.invalidate()
        }
    }

    private val baseFlags: Int
        get() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun params(w: Int, h: Int, left: Int, top: Int) =
        WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            baseFlags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = left
            y = top
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

    private fun clamp(overlay: Overlay) {
        if(::panel.isInitialized&&overlay===panel){
            overlay.params.x=overlay.params.x.coerceIn(panelBounds.left,
                (panelBounds.right-overlay.params.width).coerceAtLeast(panelBounds.left))
            overlay.params.y=overlay.params.y.coerceIn(panelBounds.top,
                (panelBounds.bottom-overlay.params.height).coerceAtLeast(panelBounds.top))
            return
        }
        overlay.params.x = overlay.params.x.coerceIn(0,
            (screen.width() - overlay.params.width).coerceAtLeast(0))
        overlay.params.y = overlay.params.y.coerceIn(0,
            (screen.height() - overlay.params.height).coerceAtLeast(0))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(handle: View, overlay: Overlay) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    downX = event.rawX
                    downY = event.rawY
                    startX = overlay.params.x
                    startY = overlay.params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        overlay.params.x = startX + dx.roundToInt()
                        overlay.params.y = startY + dy.roundToInt()
                        clamp(overlay)
                        manager.updateViewLayout(overlay.view, overlay.params)
                        publishFloatingWindows()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if(overlay===marker&&moved&&event.actionMasked==MotionEvent.ACTION_UP)
                        preferences.edit().putBoolean("${geometryKey}_tapPlaced",true).apply()
                    savePositions()
                    if (event.actionMasked == MotionEvent.ACTION_UP && !moved) view.performClick()
                    true
                }
                else -> true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeRegionDraggable() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var startWidth = 0
        var startHeight = 0
        var resizing = false
        region.view.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = region.params.x
                    startY = region.params.y
                    startWidth = region.params.width
                    startHeight = region.params.height
                    resizing = event.x > view.width - dp(36) && event.y > view.height - dp(36)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).roundToInt()
                    val dy = (event.rawY - downY).roundToInt()
                    if (resizing) {
                        val maxWidth = minOf(BobberDetector.maxSourceWidth(screen.width(), screen.height()), screen.width() - startX)
                        val maxHeight = minOf(BobberDetector.maxSourceHeight(screen.width(), screen.height()), screen.height() - startY)
                        val minSize = minOf(dp(80), maxWidth, maxHeight)
                        region.params.width = (startWidth + dx).coerceIn(minSize, maxWidth)
                        region.params.height = (startHeight + dy).coerceIn(minSize, maxHeight)
                    } else {
                        region.params.x = startX + dx
                        region.params.y = startY + dy
                    }
                    clamp(region)
                    manager.updateViewLayout(region.view, region.params)
                    snapshotLabel.text = "Crop: ${effectiveSnapshotSide()}"
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePositions()
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                }
            }
            true
        }
    }

    private fun saved(name: String, fallback: Int) =
        preferences.getInt("${geometryKey}_$name", fallback)

    private fun savePositions() {
        if (!controlsVisible) return
        preferences.edit().apply {
            mapOf("tapX" to marker.params.x, "tapY" to marker.params.y,
                "boxX" to region.params.x, "boxY" to region.params.y,
                "boxW" to region.params.width, "boxH" to region.params.height,
                "panelX" to panel.params.x, "panelY" to panel.params.y).forEach { (key, value) ->
                putInt("${geometryKey}_$key", value)
            }
        }.apply()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private inner class TapMarker : View(this@FishingAccessibilityService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { contentDescription = "Fishing button position. Drag to move." }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(if (editing) 160 else 60, 18, 34, 25)
            canvas.drawCircle(cx, cy, width / 2f - dp(3), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = Color.rgb(132, 236, 162)
            canvas.drawCircle(cx, cy, width / 2f - dp(3), paint)
            canvas.drawLine(cx - dp(9), cy, cx + dp(9), cy, paint)
            canvas.drawLine(cx, cy - dp(9), cx, cy + dp(9), paint)
        }
    }

    private inner class WatchArea : View(this@FishingAccessibilityService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { contentDescription = "Bobber detection area. Drag to move, or drag bottom right to resize." }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val edge = dp(2).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(if (editing) 25 else 0, 100, 200, 255)
            canvas.drawRect(edge, edge, width - edge, height - edge, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = edge
            paint.color = Color.rgb(117, 201, 255)
            canvas.drawRect(edge, edge, width - edge, height - edge, paint)
            if (editing) {
                val side = effectiveSnapshotSide()
                val location = IntArray(2)
                getLocationOnScreen(location)
                val plan = runCatching { RoiSampling.forScreen(screen.width(), screen.height(),
                    location[0],location[1],width,height) }.getOrNull()
                if (plan != null) {
                    val imageLeft = plan.left+(plan.width-side)/2
                    val imageTop = plan.top+(plan.height-side)/2
                    val sx = screen.width().toFloat()/plan.imageWidth
                    val sy = screen.height().toFloat()/plan.imageHeight
                    paint.style = Paint.Style.STROKE
                    canvas.drawRect(imageLeft*sx-location[0], imageTop*sy-location[1],
                        (imageLeft+side)*sx-location[0], (imageTop+side)*sy-location[1], paint)
                }
                paint.style = Paint.Style.FILL
                paint.textSize = dp(12).toFloat()
                canvas.drawText("Bobber area", dp(8).toFloat(), dp(20).toFloat(), paint)
                paint.style = Paint.Style.STROKE
                canvas.drawLine(width - dp(28).toFloat(), height - dp(5).toFloat(),
                    width - dp(5).toFloat(), height - dp(28).toFloat(), paint)
                canvas.drawLine(width - dp(16).toFloat(), height - dp(5).toFloat(),
                    width - dp(5).toFloat(), height - dp(16).toFloat(), paint)
            }
        }
    }
}
