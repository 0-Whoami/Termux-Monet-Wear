package com.termux.shared.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.ViewPropertyAnimator
import android.widget.LinearLayout
import com.termux.shared.termux.TermuxConstants
import java.io.File

class BackgroundBlur(context: Context, attributeSet: AttributeSet?) :
    LinearLayout(context, attributeSet) {
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBlurBackground()
    }

    //TODO(Fix bitmap size scale)
    private fun updateBlurBackground() {
        val file =
            File(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_BLUR_BACKGROUND)
        if (file.exists()) {
            val location = IntArray(2)
            getLocationOnScreen(location)
            val width = measuredWidth / 2
            val height = measuredHeight / 2
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply {
                strokeWidth = 1.5f
                color = Color.WHITE
            }
            val path = Path().apply {
                addRoundRect(
                    0f, 0f, width.toFloat(), height.toFloat(), 7.5f, 7.5f, Path.Direction.CW
                )
            }
            val canvas = Canvas(bitmap)
            canvas.clipPath(path)


            val out = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
            canvas.drawBitmap(out, -location[0].toFloat() / 2, -location[1].toFloat() / 2, null)
            out.recycle()

            paint.style = Paint.Style.STROKE
            canvas.drawPath(path, paint)
            background = BitmapDrawable(resources, bitmap)
        }
    }

    override fun animate(): ViewPropertyAnimator {
        updateBlurBackground()
        return super.animate()
    }
}