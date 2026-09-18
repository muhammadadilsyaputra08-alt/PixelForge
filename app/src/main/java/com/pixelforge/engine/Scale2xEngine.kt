package com.pixelforge.engine

import android.graphics.Bitmap

/** Optional pixel-art upscaler. Scale2x keeps hard pixel edges and never interpolates. */
object Scale2xEngine {
    /**
     * BUGFIX: standard Scale2x/AdvMAME2x maps a 3x3 neighborhood (A B C / D E F / G H I) to a
     * 2x2 output block using, with D=left(a) B=top(b) F=right(c) H=bottom(d):
     *   top-left     = (a!=c && b!=d && a==b) ? a : e
     *   top-right    = (a!=c && b!=d && b==c) ? c : e
     *   bottom-left  = (a!=c && b!=d && a==d) ? a : e
     *   bottom-right = (a!=c && b!=d && d==c) ? c : e
     * The previous implementation used the right guard clause but the wrong equality checks
     * AND placed the results in the wrong corners (e.g. top-left used to output `b`
     * unconditionally whenever the guard passed, without ever checking a==b, and top-right
     * reused the top-left formula) - producing visibly incorrect/smudged 2x upscales instead
     * of the crisp Scale2x result the tool is supposed to give.
     */
    fun scale2x(src: Bitmap): Bitmap {
        val w=src.width; val h=src.height
        val out=Bitmap.createBitmap(w*2,h*2,Bitmap.Config.ARGB_8888)
        for(y in 0 until h) for(x in 0 until w) {
            val e=src.getPixel(x,y)
            val a=src.getPixel((x-1).coerceAtLeast(0),y)
            val b=src.getPixel(x,(y-1).coerceAtLeast(0))
            val c=src.getPixel((x+1).coerceAtMost(w-1),y)
            val d=src.getPixel(x,(y+1).coerceAtMost(h-1))
            val guard = a!=c && b!=d
            val topLeft = if(guard && a==b) a else e
            val topRight = if(guard && b==c) c else e
            val bottomLeft = if(guard && a==d) a else e
            val bottomRight = if(guard && d==c) c else e
            out.setPixel(x*2,y*2,topLeft); out.setPixel(x*2+1,y*2,topRight)
            out.setPixel(x*2,y*2+1,bottomLeft); out.setPixel(x*2+1,y*2+1,bottomRight)
        }
        return out
    }
}
