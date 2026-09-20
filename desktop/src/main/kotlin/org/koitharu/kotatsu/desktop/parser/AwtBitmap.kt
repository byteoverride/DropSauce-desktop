package org.koitharu.kotatsu.desktop.parser

import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import java.awt.image.BufferedImage

/**
 * [Bitmap] backed by an AWT [BufferedImage].
 *
 * The parsers library abstracts bitmaps behind its own three-method interface rather than
 * `android.graphics`, and never asks for pixel access, so `BufferedImage` plus
 * `Graphics2D` covers the whole contract. Used by the descrambling parsers, which
 * Phase 1 measured at 13 of the 1270 sources.
 */
internal class AwtBitmap(val image: BufferedImage) : Bitmap {

	override val width: Int
		get() = image.width

	override val height: Int
		get() = image.height

	override fun drawBitmap(bitmap: Bitmap, src: Rect, dst: Rect) {
		val source = (bitmap as AwtBitmap).image
		val g = image.createGraphics()
		try {
			// Graphics2D takes destination corners then source corners, both exclusive on
			// the far edge, which matches Rect's right/bottom.
			g.drawImage(
				source,
				dst.left, dst.top, dst.right, dst.bottom,
				src.left, src.top, src.right, src.bottom,
				null,
			)
		} finally {
			g.dispose()
		}
	}
}
