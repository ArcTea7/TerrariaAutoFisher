package com.example.terrariaautofisher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** Move the frozen image under a fixed crop. Painting is a separate gesture mode. */
class BobberSnapshotView(context: Context, private val snapshot: SnapshotEditorModel) : View(context) {
    private val paint = Paint()
    private var bitmap: Bitmap? = Bitmap.createBitmap(snapshot.width, snapshot.height, Bitmap.Config.ARGB_8888).apply {
        val colors = IntArray(snapshot.width*snapshot.height)
        for (y in 0 until snapshot.height) for (x in 0 until snapshot.width) {
            val gray = snapshot.pixel(x,y).toInt().coerceIn(0,255)
            colors[y*snapshot.width+x] = Color.rgb(gray,gray,gray)
        }
        setPixels(colors,0,snapshot.width,0,0,snapshot.width,snapshot.height)
    }
    private val pan=CapturePan(snapshot)
    var moving = true
        set(value) { field=value; pan.end(); previousX=Float.NaN; previousY=Float.NaN; invalidate() }
    var erase = true
    var overview = false
        set(value) { field=value; pan.overview(value); previousX=Float.NaN; previousY=Float.NaN; invalidate() }
    private var previousX = Float.NaN
    private var previousY = Float.NaN

    init { contentDescription = "Bobber capture. Drag to move the image. Erase removes background; Restore brings it back." }
    fun resetMask() { snapshot.resetMask(); invalidate() }
    fun reference(): BobberReference = snapshot.reference()
    fun refresh() { invalidate() }
    fun release() { bitmap?.recycle(); bitmap=null }

    private fun cell(): Float {pan.size(width,height);return pan.scale()}
    private fun imageLeft(scale: Float): Float = pan.left()
    private fun imageTop(scale: Float): Float = pan.top()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image=bitmap ?: return
        val scale=cell()
        if (scale<=0) return
        val left=imageLeft(scale); val top=imageTop(scale)
        canvas.drawColor(Color.rgb(10,16,13))
        canvas.save()
        canvas.translate(left,top); canvas.scale(scale,scale)
        paint.isFilterBitmap=false; paint.color=Color.WHITE
        canvas.drawBitmap(image,0f,0f,paint)
        for (y in snapshot.top until snapshot.top+snapshot.side) for (x in snapshot.left until snapshot.left+snapshot.side) {
            if (!snapshot.isKept(x,y)) {
                paint.color=Color.argb(190,210,45,90)
                canvas.drawRect(x.toFloat(),y.toFloat(),x+1f,y+1f,paint)
            }
        }
        canvas.restore()
        val cropLeft=left+snapshot.left*scale; val cropTop=top+snapshot.top*scale
        val cropRight=cropLeft+snapshot.side*scale; val cropBottom=cropTop+snapshot.side*scale
        if (!overview) {
            paint.color=Color.argb(125,0,0,0)
            canvas.drawRect(0f,0f,width.toFloat(),cropTop,paint)
            canvas.drawRect(0f,cropBottom,width.toFloat(),height.toFloat(),paint)
            canvas.drawRect(0f,cropTop,cropLeft,cropBottom,paint)
            canvas.drawRect(cropRight,cropTop,width.toFloat(),cropBottom,paint)
        }
        paint.color=Color.rgb(255,205,30); paint.style=Paint.Style.STROKE
        paint.strokeWidth=2f*resources.displayMetrics.density
        canvas.drawRect(cropLeft,cropTop,cropRight,cropBottom,paint)
        paint.style=Paint.Style.FILL
    }

    private fun brush(x: Float,y: Float) {
        val scale=cell()
        if (scale<=0) return
        val px=floor((x-imageLeft(scale))/scale).toInt()
        val py=floor((y-imageTop(scale))/scale).toInt()
        snapshot.paint(px,py,1,!erase)
    }
    private fun move(x: Float,y: Float) {
        val scale=cell()
        if (scale<=0) return
        pan.move(x,y)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                cell();pan.down(event.x,event.y)
                previousX=event.x; previousY=event.y
                if (!moving) brush(event.x,event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (previousX.isNaN()) return true
                if (moving) move(event.x,event.y)
                else {
                    val distance=max(abs(event.x-previousX),abs(event.y-previousY))
                    val steps=max(1,(distance/max(1f,cell())).toInt()+1)
                    for (i in 1..steps) brush(previousX+(event.x-previousX)*i/steps,previousY+(event.y-previousY)*i/steps)
                }
                previousX=event.x; previousY=event.y; invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                previousX=Float.NaN; previousY=Float.NaN
                pan.end()
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked==MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
