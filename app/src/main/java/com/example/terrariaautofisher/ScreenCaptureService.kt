package com.example.terrariaautofisher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Screen observation plus a serial, callback-driven reel/recast sequence at the user's marker. */
class ScreenCaptureService : Service() {
    companion object {
        const val STOP = "com.example.terrariaautofisher.STOP_CAPTURE"
        private const val CHANNEL = "fishing_detection"
        private const val NOTIFICATION = 410
        @Volatile var instance: ScreenCaptureService? = null
            private set
    }

    @Volatile var monitoring = false
        private set
    val hasSession: Boolean get() = projection != null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var baseWidth = 0
    private var baseHeight = 0
    private var captureWidth = 0
    private var captureHeight = 0
    private var sampling: RoiSampling? = null
    @Volatile private var generation = 0
    private var workerGeneration = -1
    private var area = Rect()
    private var detector: BobberDetector? = null
    private var lineDetector: FishingLineTracker? = null
    private var lineSelection: FishingLineTracker.Selection? = null
    private var lastLineResult: FishingLineTracker.Result? = null
    private var activeLineMode = false
    @Volatile private var pendingLineSetup = false
    private var startAfter = 0L
    @Volatile private var snapshotPending = false
    private var snapshotSide = BobberDetector.PATCH
    @Volatile private var frozenSnapshot: SnapshotEditorModel? = null
    private var lastFrame = 0L
    private var lastPublish = 0L
    private var lastPhase = ""
    @Volatile private var bites = 0
    private var sampleWidth = BobberDetector.SIZE
    private var sampleHeight = BobberDetector.SIZE
    private var gray = FloatArray(0)
    @Volatile private var diagnosticsEnabled = false
    private val diagnosticHistory = java.util.ArrayDeque<String>()
    private var lastDiagnostic = 0L
    private var diagnosticPhase = ""
    private var diagnosticReason = ""
    private var lastPreview = 0L
    @Volatile private var previewPending = false
    @Volatile private var automationBusy = false
    private data class FloatingViews(val bounds:IntArray,val readyAfter:Long)
    @Volatile private var floatingViews=FloatingViews(IntArray(0),0L)
    private var obstructionReported=false
    private var lastBobberResult:BobberDetector.Result?=null
    private var activeBobberReference:BobberReference?=null
    private val targetRecovery=MissingTargetRecovery()
    private var runTapTarget: Point? = null
    private val fishingLoop by lazy {
        FishingLoop(
            FishingLoop.Scheduler {delay,action ->
                main.postDelayed(action,delay)
                FishingLoop.Cancellation {main.removeCallbacks(action)}
            },
            FishingLoop.Tapper {completion ->
                val overlay=FishingAccessibilityService.instance
                val target=runTapTarget
                if(monitoring&&overlay!=null&&target!=null)overlay.dispatchFishingTap(target,completion)
                else completion.finish(false)
            },
            FishingLoop.Listener {stage ->
                automationBusy=stage!=FishingLoop.Stage.WATCHING&&stage!=FishingLoop.Stage.STOPPED
                if(automationBusy||stage==FishingLoop.Stage.STOPPED)targetRecovery.reset()
                val status=when(stage){
                    FishingLoop.Stage.REELING -> "Reeling…"
                    FishingLoop.Stage.WAITING_TO_CAST -> "Preparing recast…"
                    FishingLoop.Stage.CASTING -> "Recasting…"
                    FishingLoop.Stage.SETTLING -> "Waiting for cast…"
                    FishingLoop.Stage.RETRYING_REEL -> "Retrying reel…"
                    FishingLoop.Stage.RETRYING_CAST -> "Retrying recast…"
                    else -> ""
                }
                if(monitoring)FishingAccessibilityService.instance?.automationUpdate(status)
                if(::worker.isInitialized)worker.post {
                    diagnosticHistory.addLast("# AUTOFISH elapsed_ms=${SystemClock.elapsedRealtime()} stage=$stage")
                    while(diagnosticHistory.size>600)diagnosticHistory.removeFirst()
                }
            })
    }

    private fun startFishingLoop(region:Rect):Boolean {
        targetRecovery.reset()
        val target=FishingAccessibilityService.instance?.fishingTapTarget()
        if(target==null||region.contains(target.x,target.y)){
            pauseMonitoring("Position the fishing marker on Terraria's fishing button, outside the detection area.")
            return false
        }
        runTapTarget=Point(target)
        fishingLoop.startWatching()
        return true
    }

    private fun stopFishingLoop(){
        targetRecovery.reset()
        fishingLoop.stop()
        runTapTarget=null
        automationBusy=false
        FishingAccessibilityService.instance?.restoreFishingInput()
        FishingAccessibilityService.instance?.automationUpdate("")
        obstructionReported=false
        FishingAccessibilityService.instance?.obstructionUpdate(false)
    }

    fun floatingWindowsChanged(bounds:IntArray,affectsFishingArea:Boolean){
        if(affectsFishingArea)targetRecovery.reset()
        val ready=if(affectsFishingArea)SystemClock.elapsedRealtime()+120 else floatingViews.readyAfter
        floatingViews=FloatingViews(bounds.copyOf(),ready)
    }

    private fun reportObstruction(current:Int,blocked:Boolean){
        if(obstructionReported==blocked)return
        obstructionReported=blocked
        diagnosticHistory.addLast("# VIEW elapsed_ms=${SystemClock.elapsedRealtime()} obscured=$blocked")
        while(diagnosticHistory.size>600)diagnosticHistory.removeFirst()
        main.post {
            if(instance===this&&monitoring&&generation==current)
                FishingAccessibilityService.instance?.obstructionUpdate(blocked)
        }
    }

    private fun windowsCoverBobber(windows:IntArray,result:BobberDetector.Result?,candidate:Boolean=false):Boolean{
        if(windows.isEmpty())return false
        val plan=sampling?:return false
        if(result==null||(!candidate&&(result.phase!="WATCHING"||!result.hasReliablePosition)))
            return OverlayOcclusion.intersects(windows,area.left,area.top,area.right,area.bottom)
        val x=if(candidate)result.candidateX else result.reliableX
        val y=if(candidate)result.candidateY else result.reliableY
        if(x<0||y<0)return false
        val pad=if(candidate)0 else 12
        val l=kotlin.math.floor((plan.left+x-pad)*baseWidth.toDouble()/captureWidth).toInt()
        val t=kotlin.math.floor((plan.top+y-pad)*baseHeight.toDouble()/captureHeight).toInt()
        val r=kotlin.math.ceil((plan.left+x+result.referenceSide+pad)*baseWidth.toDouble()/captureWidth).toInt()
        val b=kotlin.math.ceil((plan.top+y+result.referenceSide+pad)*baseHeight.toDouble()/captureHeight).toInt()
        return OverlayOcclusion.intersects(windows,l,t,r,b)
    }

    private fun windowsCoverLine(windows:IntArray):Boolean{
        if(windows.isEmpty())return false
        val last=lastLineResult
        if(last!=null&&(last.visible<.60||last.phase=="SEARCHING"||last.phase=="RECAST"))
            return OverlayOcclusion.intersects(windows,area.left,area.top,area.right,area.bottom)
        val centers=lineDetector?.inspectionCenters()?:return false
        return OverlayOcclusion.line(windows,centers,captureWidth,captureHeight,baseWidth,baseHeight)
    }

    fun enableDiagnostics(enabled: Boolean) {
        diagnosticsEnabled = enabled
    }

    fun markMissedBite() {
        val at = SystemClock.elapsedRealtime()
        worker.post {
            diagnosticHistory.addLast("# USER_MARK_MISSED_BITE elapsed_ms=$at flagged=$bites")
            while (diagnosticHistory.size > 600) diagnosticHistory.removeFirst()
            main.post {
                if (instance === this) android.widget.Toast.makeText(this,
                    "Miss noted. Copy log after the test", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun copyDiagnostics() {
        worker.post {
            val text = buildString {
                append("Terraria Auto Fisher — automatic reel/recast test 1\n")
                append("Numeric detection history.\n")
                append("generation,elapsed_ms,phase,match,candidate_x,candidate_y,reliable_x,reliable_y,reason,search,wide_scans,analysis_us,frame_gap_ms,local_error,changed_fraction,rest_match,down_px,pending_ms,armed,line_displacement,line_visible,line_support,line_threshold,line_noise,line_points,line_area_enabled,line_area_scans,line_path_generation\n")
                diagnosticHistory.forEach { append(it); append('\n') }
            }
            main.post {
                if (instance === this) {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Fishing diagnostics", text))
                    android.widget.Toast.makeText(this, "Diagnostic history copied", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun recordDiagnostics(current: Int, now: Long, result: BobberDetector.Result, analysisUs: Long, frameGapMs: Long) {
        if (result.phase != diagnosticPhase || result.reason != diagnosticReason ||
            result.pendingMs > 0 || result.reason == "LOCAL_BITE_CHECK" || result.bite || now-lastDiagnostic >= 200) {
            diagnosticPhase = result.phase
            diagnosticReason = result.reason
            lastDiagnostic = now
            diagnosticHistory.addLast("$current,$now,${result.phase},${result.confidence}," +
                "${result.candidateX},${result.candidateY},${result.reliableX},${result.reliableY}," +
                "${result.reason},${result.searchSource},${result.wideSearches},$analysisUs,$frameGapMs," +
                "${result.localError},${result.changedFraction},${result.restMatch}," +
                "${result.downwardPixels},${result.pendingMs},${result.armed}," +
                "${lastLineResult?.displacement ?: 0.0},${lastLineResult?.visible ?: 0.0}," +
                "${lastLineResult?.support ?: 0.0},${lastLineResult?.threshold ?: 0.0}," +
                "${lastLineResult?.noise ?: 0.0},${lastLineResult?.points ?: 0}," +
                "${lastLineResult?.areaMode ?: false},${lastLineResult?.areaSearches ?: 0},${lastLineResult?.pathGeneration ?: 0}")
            while (diagnosticHistory.size > 600) diagnosticHistory.removeFirst()
        }
        // Numeric history is always available; Inspect controls only the image preview.
        if (activeLineMode || !diagnosticsEnabled || previewPending || now-lastPreview < 200) return
        lastPreview = now
        // Bounded display-only copy. The actual matcher continues using the original gray array.
        val previewWidth = minOf(160, sampleWidth)
        val previewHeight = (sampleHeight.toFloat()*previewWidth/sampleWidth).roundToInt().coerceAtLeast(1)
        val pixels = IntArray(previewWidth*previewHeight)
        for (y in 0 until previewHeight) for (x in 0 until previewWidth) {
            val sx = (x*sampleWidth/previewWidth).coerceAtMost(sampleWidth-1)
            val sy = (y*sampleHeight/previewHeight).coerceAtMost(sampleHeight-1)
            val value = gray[sy*sampleWidth+sx].toInt().coerceIn(0,255)
            pixels[y*previewWidth+x] = Color.rgb(value,value,value)
        }
        val width = sampleWidth
        val height = sampleHeight
        val count = bites
        previewPending = true
        main.post {
            try {
                if (instance === this && diagnosticsEnabled && current == generation && monitoring)
                    FishingAccessibilityService.instance?.updateTrackingPreview(pixels, previewWidth,
                        previewHeight, width, height, result, analysisUs/1000f, count)
            } finally { previewPending = false }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            fail("Screen sharing stopped. Tap Start to start a new session.")
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width != baseWidth || height != baseHeight) {
                fail("Screen size changed. Reopen the controls in landscape and choose Entire screen.")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        thread = HandlerThread("FishingImageAnalysis").apply { start() }
        worker = Handler(thread.looper)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Fishing detection", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Screen-capture session for the fishing detector" }
        )
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            monitoring=false
            stopFishingLoop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null || projection != null) return START_NOT_STICKY
        val request = intent.getLongExtra(CapturePermissionActivity.REQUEST, -1L)
        val overlay = FishingAccessibilityService.instance
        if (overlay?.acceptsCaptureRequest(request) != true) {
            stopSelf()
            return START_NOT_STICKY
        }
        val grant = intent.getParcelableExtra<Intent>(CapturePermissionActivity.DATA)
        val region = intent.getParcelableExtra<Rect>(CapturePermissionActivity.AREA)
        baseWidth = intent.getIntExtra(CapturePermissionActivity.WIDTH, 0)
        baseHeight = intent.getIntExtra(CapturePermissionActivity.HEIGHT, 0)
        if (grant == null || region == null || baseWidth <= 0 || baseHeight <= 0) {
            fail("Capture setup was incomplete. Tap Start to retry.")
            return START_NOT_STICKY
        }
        try {
            // Android 14+: foreground service must be active before getMediaProjection().
            startForeground(NOTIFICATION, notification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
            if (bounds.width() != baseWidth || bounds.height() != baseHeight) {
                fail("The screen rotated. Set the box in landscape and press Start again.")
                return START_NOT_STICKY
            }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val capture = manager.getMediaProjection(
                intent.getIntExtra(CapturePermissionActivity.RESULT, 0), grant)
                ?: throw IllegalStateException("Screen capture was not granted")
            projection = capture
            capture.registerCallback(projectionCallback, main)
            val scale = minOf(1f, 1280f / max(baseWidth, baseHeight))
            val width = (baseWidth * scale).roundToInt().coerceAtLeast(1)
            val height = (baseHeight * scale).roundToInt().coerceAtLeast(1)
            captureWidth = width
            captureHeight = height
            val images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            reader = images
            images.setOnImageAvailableListener({ source -> readFrame(source) }, worker)
            display = capture.createVirtualDisplay("TerrariaBiteObservation", width, height,
                resources.configuration.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                images.surface, null, null)
            // Permission approval only prepares capture. The user chooses when to take the snapshot.
            FishingAccessibilityService.instance?.detectionUpdate("READY",
                "Tap Capture.", 0f, bites, false)
            updateNotification("Ready")
        } catch (error: RuntimeException) {
            fail("Couldn't start screen sharing. Try again.")
        }
        return START_NOT_STICKY
    }

    private fun referenceFile() = File(filesDir, "bobber-grid2-${baseWidth}x${baseHeight}.bin")
    val hasReference: Boolean get() = baseWidth > 0 && referenceFile().isFile

    private fun checkedRegion(region: Rect): Rect? {
        val bounded = Rect(region)
        if (!bounded.intersect(0, 0, baseWidth, baseHeight) || bounded.width() < 32 || bounded.height() < 32) {
            pauseMonitoring("Detection box is outside the screen. Reposition it.")
            return null
        }
        return bounded
    }

    private fun prepareRegion(bounds: Rect) {
        area = bounds
        val plan = RoiSampling.of(baseWidth, baseHeight, captureWidth, captureHeight,
            area.left, area.top, area.width(), area.height())
        sampling = plan
        sampleWidth = plan.width
        sampleHeight = plan.height
        gray = FloatArray(sampleWidth * sampleHeight)
        lastFrame = 0L; lastPublish = 0L; lastPhase = ""
        lastDiagnostic = 0L; diagnosticPhase = ""; diagnosticReason = ""; lastPreview = 0L
    }

    /** Explicit snapshot: one frame only, after the positioning overlays have disappeared. */
    fun captureSnapshot(region: Rect, side: Int, forLine: Boolean = false) {
        if (projection == null) return
        val bounded = checkedRegion(region) ?: return
        generation++
        val current = generation
        monitoring = false
        stopFishingLoop()
        snapshotPending = true
        pendingLineSetup = forLine
        FishingAccessibilityService.instance?.detectionUpdate("SNAPSHOT", "Taking capture…", 0f, bites, false)
        FishingAccessibilityService.instance?.hideForSnapshot()
        worker.post {
            if (current != generation || !snapshotPending) return@post
            try {
                prepareRegion(bounded)
                snapshotSide = side
                startAfter = SystemClock.elapsedRealtime() + 150
                workerGeneration = current
            } catch (error: IllegalArgumentException) {
                main.post { if (current == generation) pauseMonitoring("Enlarge the box and tap Capture again") }
            }
        }
        main.postDelayed({
            if (instance === this && current == generation && snapshotPending)
                pauseMonitoring("Couldn't capture the screen. Try again.")
        }, 5000)
    }

    fun reopenSnapshot(forLine: Boolean = false): Boolean {
        val snapshot = frozenSnapshot ?: return false
        if (projection == null || (forLine && !snapshot.hasColor())) return false
        pauseMonitoring("Editing capture")
        if (forLine) FishingAccessibilityService.instance?.showLineSnapshot(snapshot)
        else FishingAccessibilityService.instance?.showSnapshot(snapshot)
        return true
    }

    fun saveReference(reference: BobberReference, onSaved: (Boolean) -> Unit) {
        worker.post {
            val saved = runCatching { reference.save(referenceFile()) }.isSuccess
            main.post { if (instance === this) onSaved(saved) }
        }
    }

    /** Resume uses the stored snapshot; no image is learned from live fishing frames. */
    fun beginMonitoring(region: Rect, forLine: Boolean = false, sensitivity: Double = .20, useArea: Boolean = false, margin: Int = 16) {
        if (projection == null) return
        if (forLine) { beginLineMonitoring(region, sensitivity, useArea, margin); return }
        val bounded = checkedRegion(region) ?: return
        if (!hasReference) {
            pauseMonitoring("Capture the bobber first.")
            return
        }
        generation++
        val current = generation
        snapshotPending = false
        monitoring = true
        if(!startFishingLoop(bounded))return
        FishingAccessibilityService.instance?.detectionUpdate("STARTING", "Finding bobber…", 0f, bites, false)
        worker.post {
            if (current != generation || !monitoring) return@post
            try {
                activeLineMode = false; lineDetector = null; lineSelection = null; lastLineResult = null
                val reference = BobberReference.load(referenceFile())
                activeBobberReference=reference;lastBobberResult=null
                prepareRegion(bounded)
                detector = BobberDetector(sampleWidth, sampleHeight, reference.side).apply { setReference(reference) }
                diagnosticHistory.addLast("# generation $current; analysis ${sampleWidth}x${sampleHeight}; reference ${reference.side}; screen ${baseWidth}x${baseHeight}; area $area")
                while (diagnosticHistory.size > 600) diagnosticHistory.removeFirst()
                startAfter = SystemClock.elapsedRealtime() + 150
                workerGeneration = current
            } catch (error: Exception) {
                main.post {
                    if (current == generation) pauseMonitoring("Snapshot unavailable or too large for this box. Tap Capture to retake")
                }
            }
        }
        updateNotification("Finding bobber…")
    }

    private fun lineFile() = File(filesDir, "fishing-line1-${baseWidth}x${baseHeight}.bin")

    fun saveLine(selection: FishingLineTracker.Selection, onSaved: (Boolean) -> Unit) {
        worker.post {
            val saved = runCatching {
                require(selection.width == captureWidth && selection.height == captureHeight)
                selection.save(lineFile())
            }.isSuccess
            main.post { if (instance === this) onSaved(saved) }
        }
    }

    fun lineBoundsOnScreen(margin: Int = 12): Rect? {
        if (baseWidth <= 0 || !lineFile().isFile) return null
        return runCatching {
            val line=FishingLineTracker.Selection.load(lineFile())
            val sx=baseWidth.toDouble()/line.width; val sy=baseHeight.toDouble()/line.height
            val left=((line.x.minOrNull()!!-margin)*sx).toInt().coerceIn(0,baseWidth-32)
            val top=((line.y.minOrNull()!!-margin)*sy).toInt().coerceIn(0,baseHeight-32)
            val right=((line.x.maxOrNull()!!+margin+1)*sx).toInt().coerceIn(left+32,baseWidth)
            val bottom=((line.y.maxOrNull()!!+margin+1)*sy).toInt().coerceIn(top+32,baseHeight)
            Rect(left,top,right,bottom)
        }.getOrNull()
    }

    private fun beginLineMonitoring(region: Rect, sensitivity: Double, useArea: Boolean, margin: Int) {
        val bounded=checkedRegion(region) ?: return
        if (!lineFile().isFile) {
            pauseMonitoring("Capture and select the line first.")
            return
        }
        generation++
        val current=generation
        snapshotPending=false; monitoring=true
        if(!startFishingLoop(bounded))return
        FishingAccessibilityService.instance?.detectionUpdate("STARTING","Finding line…",0f,bites,false)
        worker.post {
            if (current != generation || !monitoring) return@post
            try {
                val line=FishingLineTracker.Selection.load(lineFile())
                require(line.width == captureWidth && line.height == captureHeight)
                prepareRegion(bounded)
                activeLineMode=true; detector=null; lastLineResult=null; lineSelection=line
                lineDetector=FishingLineTracker(line,sensitivity,useArea,margin)
                diagnosticHistory.addLast("# generation $current; mode ${if(useArea) "LINE_AREA_2" else "LINE_FIXED_1"}; margin $margin; capture ${captureWidth}x${captureHeight}; points ${line.x.size}; sensitivity $sensitivity; sampled_rgb ${Integer.toHexString(line.color)}")
                while(diagnosticHistory.size>600) diagnosticHistory.removeFirst()
                startAfter=SystemClock.elapsedRealtime()+150; workerGeneration=current
            } catch(error: Exception) {
                main.post { if (current == generation) pauseMonitoring("Line unavailable or screen changed. Capture the line again") }
            }
        }
        updateNotification("Waiting for line…")
    }

    fun pauseMonitoring(message: String = "Paused.") {
        monitoring = false
        snapshotPending = false
        generation++
        stopFishingLoop()
        FishingAccessibilityService.instance?.detectionUpdate("PAUSED", message, 0f, bites, false)
        if (projection != null) updateNotification("Paused.")
    }

    private fun readFrame(source: ImageReader) {
        val image = try { source.acquireLatestImage() } catch (error: IllegalStateException) { null }
            ?: return
        try {
            val current = generation
            if ((!monitoring && !snapshotPending) || workerGeneration != current) return
            // Still drain/close capture frames, but never detect bites in our own reel/cast animation.
            if(automationBusy&&!snapshotPending){targetRecovery.reset();return}
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrame < 45 || now < startAfter) return
            val windows=floatingViews
            if(!snapshotPending){
                if(now<windows.readyAfter){targetRecovery.reset();return}
                val blocked=if(activeLineMode)windowsCoverLine(windows.bounds)
                    else windowsCoverBobber(windows.bounds,lastBobberResult)
                if(blocked){targetRecovery.reset();reportObstruction(current,true);return}
                if(obstructionReported&&!activeLineMode){
                    activeBobberReference?.let {reference ->
                        detector=BobberDetector(sampleWidth,sampleHeight,reference.side).apply {setReference(reference)}
                    }
                }
                reportObstruction(current,false)
            }
            val frameGapMs = if (lastFrame == 0L) 0L else now - lastFrame
            lastFrame = now
            val analysisStart = System.nanoTime()
            if (!snapshotPending && activeLineMode) {
                val plane = image.planes[0]
                val frame = FishingLineTracker.rgba(plane.buffer,plane.rowStride,plane.pixelStride,image.width,image.height)
                val line = lineDetector?.process(frame,now) ?: return
                if (current != generation || !monitoring) return
                lastLineResult = line
                checkTargetRecovery(current,now,line.visible>=.60,
                    !line.bite&&line.visible.isFinite())
                val result = BobberDetector.Result(line.phase,line.message,line.visible.toFloat(),line.bite,
                    -1,-1,-1,-1,0,line.reason,"selected line",false,0)
                result.pendingMs = line.pendingMs
                if (line.bite) {bites++;automationBusy=true}
                recordDiagnostics(current,now,result,(System.nanoTime()-analysisStart)/1000,frameGapMs)
                publishLinePreview(current,now,frame,line,(System.nanoTime()-analysisStart)/1000000f)
                publish(current,line.phase,line.message,line.visible.toFloat(),line.bite)
                return
            }
            sampleRegion(image)
            if (snapshotPending) {
                try {
                    val plan = sampling ?: throw IllegalArgumentException("Snapshot area unavailable")
                    val plane = image.planes[0]
                    val snapshot = SnapshotEditorModel.fromRgba(plane.buffer, plane.rowStride, plane.pixelStride,
                        image.width, image.height, snapshotSide,
                        plan.left + (sampleWidth-snapshotSide)/2, plan.top + (sampleHeight-snapshotSide)/2, pendingLineSetup)
                    if (current != generation || !snapshotPending) return
                    frozenSnapshot = snapshot
                    snapshotPending = false
                    main.post {
                        if (instance === this && current == generation)
                            if (snapshot.hasColor()) FishingAccessibilityService.instance?.showLineSnapshot(snapshot)
                            else FishingAccessibilityService.instance?.showSnapshot(snapshot)
                    }
                } catch (error: IllegalArgumentException) {
                    snapshotPending = false
                    main.post {
                        if (instance === this && current == generation)
                            pauseMonitoring("Couldn't capture the screen. Try again.")
                    }
                }
                return
            }
            val result = detector?.process(gray, now) ?: return
            if (current != generation || !monitoring) return
            // A wide search can find a floating window. Never act on that candidate.
            if(windowsCoverBobber(windows.bounds,result,true)){
                reportObstruction(current,true);return
            }
            lastBobberResult=result
            checkTargetRecovery(current,now,BobberDetector.hasRestingCandidate(result),
                !result.bite&&result.phase!="NEEDS_REFERENCE"&&result.confidence.isFinite())
            if (result.bite) {bites++;automationBusy=true}
            recordDiagnostics(current, now, result, (System.nanoTime()-analysisStart)/1000, frameGapMs)
            publish(current, result.phase, result.message, result.confidence, result.bite)
        } catch (error: RuntimeException) {
            main.post { if (instance === this) fail("Screen sharing failed. Tap Start to retry.") }
        } finally {
            image.close()
        }
    }

    private fun checkTargetRecovery(current:Int,now:Long,present:Boolean,eligible:Boolean){
        val ticket=targetRecovery.observe(now,present,eligible&&!automationBusy)
        if(ticket==0L)return
        main.post {
            if(instance!==this||generation!=current||!monitoring||automationBusy)return@post
            if(!targetRecovery.claim(ticket,SystemClock.elapsedRealtime()))return@post
            if(fishingLoop.recoverCast())worker.post {
                if(current==generation&&monitoring){
                    val at=SystemClock.elapsedRealtime()
                    if(activeLineMode)lineDetector?.expectCast(at) else detector?.expectCast(at)
                    diagnosticHistory.addLast("# RECOVERY_CAST elapsed_ms=$at mode=${if(activeLineMode) "line" else "bobber"} missing_ms=${MissingTargetRecovery.MISSING_MS}")
                    while(diagnosticHistory.size>600)diagnosticHistory.removeFirst()
                }
            }
        }
    }

    /** Small display-only preview using the existing capture, at most five updates per second. */
    private fun publishLinePreview(current:Int,now:Long,frame:FishingLineTracker.Image,
                                   result:FishingLineTracker.Result,analysisMs:Float){
        if(!diagnosticsEnabled||previewPending||now-lastPreview<200)return
        lastPreview=now
        val left=(area.left.toDouble()*frame.width()/baseWidth).roundToInt().coerceIn(0,frame.width()-1)
        val top=(area.top.toDouble()*frame.height()/baseHeight).roundToInt().coerceIn(0,frame.height()-1)
        val right=(area.right.toDouble()*frame.width()/baseWidth).roundToInt().coerceIn(left+1,frame.width())
        val bottom=(area.bottom.toDouble()*frame.height()/baseHeight).roundToInt().coerceIn(top+1,frame.height())
        val previewScale=minOf(1.0,160.0/(right-left),160.0/(bottom-top))
        val width=((right-left)*previewScale).roundToInt().coerceAtLeast(1)
        val height=((bottom-top)*previewScale).roundToInt().coerceAtLeast(1)
        val pixels=IntArray(width*height)
        for(y in 0 until height)for(x in 0 until width)
            pixels[y*width+x]=DetectionUi.grayscalePixel(frame.rgb(left+x*(right-left)/width,top+y*(bottom-top)/height))
        val path=lineDetector?.inspectionPath(frame)?:return
        for(i in path[0].indices){
            path[0][i]=(path[0][i]-left)*width/(right-left)
            path[1][i]=(path[1][i]-top)*height/(bottom-top)
        }
        val count=bites
        previewPending=true
        main.post {
            try {
                if(instance===this&&diagnosticsEnabled&&current==generation&&monitoring)
                    FishingAccessibilityService.instance?.updateLinePreview(pixels,width,height,path,result,analysisMs,count)
            }finally {previewPending=false}
        }
    }

    private fun sampleRegion(image: Image) {
        val plan = sampling ?: throw IllegalStateException("Capture crop was not prepared")
        check(image.width == plan.imageWidth && image.height == plan.imageHeight)
        val plane = image.planes[0]
        plan.readGray(plane.buffer, plane.rowStride, plane.pixelStride, gray)
    }

    private fun publish(current: Int, phase: String, message: String, confidence: Float, event: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val changed = phase != lastPhase
        if (!event && !changed && now - lastPublish < 500) return
        lastPhase = phase
        lastPublish = now
        val count = bites
        main.post {
            if (instance === this && generation == current && monitoring) {
                FishingAccessibilityService.instance?.detectionUpdate(phase, message, confidence, count, event)
                if(event)fishingLoop.bite()
                if (changed) updateNotification(DetectionUi.message(activeLineMode,phase,message))
            }
        }
    }

    private fun fail(message: String) {
        monitoring = false
        snapshotPending = false
        generation++
        stopFishingLoop()
        FishingAccessibilityService.instance?.detectionUpdate("STOPPED", message, 0f, bites, false)
        stopSelf()
    }

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, ScreenCaptureService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Terraria Auto Fisher")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Stop", stop).build())
            .build()
    }

    private fun updateNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, notification(message))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitoring = false
        snapshotPending = false
        generation++
        stopFishingLoop()
        if (instance === this) instance = null
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        display = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        val images = reader
        reader = null
        worker.post {
            images?.close()
            detector = null; lineDetector = null; lineSelection = null; lastLineResult = null
            frozenSnapshot = null
            thread.quitSafely()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        FishingAccessibilityService.instance?.captureSessionEnded()
        super.onDestroy()
    }
}
