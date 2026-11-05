package com.example.terrariaautofisher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Display-only copy of the watched region. Its overlay must remain OUTSIDE that region. */
class TrackingPreview(context: Context) : View(context) {
    private val paint = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private var bitmap: Bitmap? = null
    private var result: BobberDetector.Result? = null
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var linePath: Array<FloatArray>? = null

    init { contentDescription = "Live bobber preview." }

    fun update(pixels: IntArray, width: Int, height: Int, sourceWidth: Int, sourceHeight: Int,
               result: BobberDetector.Result) {
        if (bitmap?.width != width || bitmap?.height != height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        bitmap?.setPixels(pixels, 0, width, 0, 0, width, height)
        this.sourceWidth = sourceWidth
        this.sourceHeight = sourceHeight
        this.result = result
        linePath = null
        invalidate()
    }

    fun updateLine(pixels: IntArray, width: Int, height: Int, path: Array<FloatArray>) {
        if (bitmap?.width != width || bitmap?.height != height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        bitmap?.setPixels(pixels,0,width,0,0,width,height)
        sourceWidth=width;sourceHeight=height;result=null;linePath=path
        contentDescription="Live fishing line preview."
        invalidate()
    }

    fun release() { bitmap?.recycle(); bitmap = null; result = null; linePath=null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(15, 22, 18))
        val image = bitmap ?: return
        val scale = min(width.toFloat()/sourceWidth, height.toFloat()/sourceHeight)
        val left = (width-sourceWidth*scale)/2
        val top = (height-sourceHeight*scale)/2
        canvas.drawBitmap(image, Rect(0, 0, image.width, image.height),
            RectF(left, top, left+sourceWidth*scale, top+sourceHeight*scale), paint)
        linePath?.let { points ->
            stroke.color=Color.rgb(40,225,120)
            for(i in 1 until points[0].size){
                if(points[0][i-1].isFinite()&&points[0][i].isFinite()&&points[1][i-1].isFinite()&&points[1][i].isFinite())
                    canvas.drawLine(left+points[0][i-1]*scale,top+points[1][i-1]*scale,
                        left+points[0][i]*scale,top+points[1][i]*scale,stroke)
            }
        }
        val state = result ?: return
        fun box(x: Int, y: Int, color: Int) {
            if (x < 0 || y < 0) return
            stroke.color = color
            canvas.drawRect(left+x*scale, top+y*scale,
                left+(x+state.referenceSide)*scale, top+(y+state.referenceSide)*scale, stroke)
        }
        if (state.hasReliablePosition) box(state.reliableX, state.reliableY, Color.rgb(40, 225, 120))
        box(state.candidateX, state.candidateY, Color.rgb(255, 205, 30))
    }
}
