package com.nethunter.kalidroid

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** A LinearLayout that never grows wider than R.dimen.card_max_width, for tablets and landscape. */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val maxWidth = resources.getDimensionPixelSize(R.dimen.card_max_width)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val capped = if (width > maxWidth) {
            MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.getMode(widthMeasureSpec))
        } else {
            widthMeasureSpec
        }
        super.onMeasure(capped, heightMeasureSpec)
    }
}
