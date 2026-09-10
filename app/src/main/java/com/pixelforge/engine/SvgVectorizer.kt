package com.pixelforge.engine

import android.graphics.Bitmap
import android.graphics.Color
import java.util.Locale

/** Converts opaque pixel runs to crisp SVG rects. Transparent pixels are skipped. */
object SvgVectorizer {
    // BUGFIX: `\n` inside a Kotlin triple-quoted ("""...""") raw string is NOT an escape
    // sequence - it was being written out as the two literal characters backslash+n instead
    // of an actual newline, so every exported .svg was a single line full of stray "\n" text
    // nodes between elements instead of a normally-formatted file. Using StringBuilder.appendLine
    // (which appends a real line separator) fixes this without changing the SVG's visual output.
    fun toSvg(bitmap: Bitmap): String {
        val sb=StringBuilder()
        sb.appendLine("""<svg xmlns="http://www.w3.org/2000/svg" width="${bitmap.width}" height="${bitmap.height}" viewBox="0 0 ${bitmap.width} ${bitmap.height}" shape-rendering="crispEdges">""")
        for(y in 0 until bitmap.height){
            var x=0
            while(x<bitmap.width){
                val c=bitmap.getPixel(x,y)
                if(Color.alpha(c)==0){x++;continue}
                val start=x
                while(x+1<bitmap.width && bitmap.getPixel(x+1,y)==c) x++
                val n=x-start+1
                val hex=String.format(Locale.US,"#%06X",0xFFFFFF and c)
                val opacity=Color.alpha(c)/255f
                if(opacity>=0.999f) sb.appendLine("""<rect x="$start" y="$y" width="$n" height="1" fill="$hex"/>""")
                else sb.appendLine("""<rect x="$start" y="$y" width="$n" height="1" fill="$hex" fill-opacity="$opacity"/>""")
                x++
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }
}
