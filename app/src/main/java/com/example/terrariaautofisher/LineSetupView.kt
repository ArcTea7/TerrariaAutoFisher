package com.example.terrariaautofisher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** A tap chooses the rendered line color; no palette or RGB entry is required. */
class LineSetupView(context: Context, private val snapshot: SnapshotEditorModel) : View(context) {
    private val image = snapshot.colorImage()
    private var bitmap: Bitmap? = Bitmap.createBitmap(snapshot.width, snapshot.height, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(snapshot.width*snapshot.height)
        for(y in 0 until snapshot.height) for(x in 0 until snapshot.width)
            pixels[y*snapshot.width+x] = image.rgb(x,y) or -0x1000000
        setPixels(pixels,0,snapshot.width,0,0,snapshot.width,snapshot.height)
    }
    private val paint = Paint()
    var selection: FishingLineTracker.Selection? = null
        private set
    private var fullSelection: FishingLineTracker.Selection? = null
    var onSelection: ((String) -> Unit)? = null
    var selecting = false
    var showArea = false
        set(value){field=value;invalidate()}
    var areaMargin = 16
        set(value){field=value.coerceIn(8,48);invalidate()}
    private val navigation=LineEditorGesture(snapshot.width,snapshot.height)
    private val pinch = ScaleGestureDetector(context, object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            navigation.scaleBy(detector.scaleFactor,detector.focusX,detector.focusY)
            invalidate();return true
        }
    })
    init { contentDescription="Frozen screen. Zoom or pan, then tap a clear middle section of fishing line. Green shows the automatically traced path." }
    private fun scale()=navigation.scale()
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        super.onSizeChanged(w,h,oldw,oldh);navigation.size(w,h)
    }
    fun zoomBy(factor: Float){navigation.zoomBy(factor);invalidate()}
    fun overview(){navigation.overview();invalidate()}
    fun trim(start: Boolean){
        try {selection=selection?.trim(start);onSelection?.invoke("Check the shortened green path, then Save.");invalidate()}
        catch(error:IllegalArgumentException){onSelection?.invoke(error.message?:"Keep a longer line section")}
    }
    fun resetPath(){selection=fullSelection;invalidate()}
    fun release(){bitmap?.recycle();bitmap=null}
    override fun onDraw(canvas: Canvas){
        super.onDraw(canvas);val bitmap=bitmap?:return;val s=scale();if(s<=0)return
        canvas.drawColor(Color.rgb(10,16,13));canvas.save()
        canvas.translate(width/2f-navigation.centerX()*s,height/2f-navigation.centerY()*s);canvas.scale(s,s)
        paint.isFilterBitmap=false;paint.color=Color.WHITE;paint.style=Paint.Style.FILL
        canvas.drawBitmap(bitmap,0f,0f,paint)
        selection?.let { line ->
            if(showArea){
                val l=(line.x.minOrNull()!!-areaMargin).coerceAtLeast(5).toFloat()
                val t=(line.y.minOrNull()!!-areaMargin).coerceAtLeast(5).toFloat()
                val r=(line.x.maxOrNull()!!+areaMargin).coerceAtMost(snapshot.width-6).toFloat()
                val b=(line.y.maxOrNull()!!+areaMargin).coerceAtMost(snapshot.height-6).toFloat()
                paint.style=Paint.Style.FILL;paint.color=Color.argb(24,60,160,255)
                canvas.drawRect(l,t,r,b,paint)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f/s;paint.color=Color.rgb(70,165,255)
                canvas.drawRect(l,t,r,b,paint)
            }
            paint.style=Paint.Style.STROKE;paint.strokeWidth=12f
            paint.color=Color.argb(45,0,255,130)
            for(i in 1 until line.x.size)canvas.drawLine(line.x[i-1].toFloat(),line.y[i-1].toFloat(),line.x[i].toFloat(),line.y[i].toFloat(),paint)
            paint.strokeWidth=1.5f/s;paint.color=Color.rgb(50,255,145)
            for(i in 1 until line.x.size)canvas.drawLine(line.x[i-1].toFloat(),line.y[i-1].toFloat(),line.x[i].toFloat(),line.y[i].toFloat(),paint)
            paint.color=Color.YELLOW;paint.strokeWidth=2f/s
            canvas.drawCircle(line.seedX.toFloat(),line.seedY.toFloat(),5f/s,paint)
            paint.style=Paint.Style.FILL
        }
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!isEnabled)return true
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                parent?.requestDisallowInterceptTouchEvent(true)
                navigation.down(event.x,event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_POINTER_UP->navigation.multiple()
        }
        pinch.onTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_MOVE->{
                navigation.move(event.x,event.y,event.pointerCount,pinch.isInProgress,
                    !selecting,8*resources.displayMetrics.density)
                invalidate()
            }
            MotionEvent.ACTION_UP->{
                if(selecting&&navigation.canSelect()){
                    val s=scale()
                    if(s>0){
                        val x=navigation.imageX(event.x).toInt();val y=navigation.imageY(event.y).toInt()
                        selection=null;fullSelection=null
                        try {selection=FishingLineTracker.trace(image,x,y)
                            fullSelection=selection
                            onSelection?.invoke("Check the green outline, then Save.")
                        } catch(error: IllegalArgumentException){onSelection?.invoke("Couldn't find the line. Try another clear section.")}
                        invalidate()
                    }
                }
                navigation.end();parent?.requestDisallowInterceptTouchEvent(false);performClick()
            }
            MotionEvent.ACTION_CANCEL->{navigation.end();parent?.requestDisallowInterceptTouchEvent(false)}
        }
        return true
    }
    override fun performClick(): Boolean {super.performClick();return true}
}
