package dev.doppel.sdk

import kotlin.math.max
import kotlin.math.min

/** Physical pixels are derived from both density and the short display edge. */
internal object FeedbackMotion {
    fun edgeWidth(density: Float, shortEdge: Int) = max(5f * density, shortEdge * 0.0055f)
    /** Non-overlapping [left, top, right, bottom] strips retain rounded corners and the outer glow. */
    fun edgeRegions(width:Int,height:Int,density:Float):List<List<Int>> {
        require(width>0 && height>0 && density>0)
        val band=kotlin.math.ceil(28f*density+edgeWidth(density,min(width,height))*0.66f).toInt()
            .coerceIn(1,max(1,min(width,height)/2))
        return listOf(listOf(0,0,width,band),listOf(0,height-band,width,height),
            listOf(0,band,band,height-band),listOf(width-band,band,width,height-band))
            .filter {it[2]>it[0] && it[3]>it[1]}
    }
    fun radius(density: Float, shortEdge: Int) = max(23f * density, shortEdge * 0.024f)
    fun travel(elapsed: Long): Float {
        val t = (elapsed / 620f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    fun pressScale(elapsed: Long, longPress: Boolean): Float {
        val release = if (longPress) 570L else 170L
        return when {
            elapsed < 120 -> 1f - 0.3f * (elapsed / 120f)
            elapsed < release -> 0.7f
            else -> 0.7f + 0.55f * min(1f, (elapsed - release) / 240f)
        }
    }
}
