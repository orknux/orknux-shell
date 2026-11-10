package io.mszymanski.orknux.shell

import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Java's own clipboard, through AWT: the last resort on a JVM with a display,
 * where no clipboard tool was found. Reached only by name from [Clipboards], so
 * the native binary - built without AWT - never carries it.
 */
class AwtClipboard : Clipboard, AvailableOrNot {
    override val name = "Java (AWT)"

    override fun available(): Boolean = !GraphicsEnvironment.isHeadless() && runCatching { Toolkit.getDefaultToolkit().systemClipboard }.isSuccess

    private val clipboard get() = Toolkit.getDefaultToolkit().systemClipboard

    override fun read(): Clipped {
        val held = runCatching { clipboard.getContents(null) }.getOrNull() ?: return Clipped.Empty
        if (held.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val image = held.getTransferData(DataFlavor.imageFlavor) as Image
            val buffered = image as? BufferedImage ?: BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB).also {
                it.graphics.drawImage(image, 0, 0, null)
            }
            val out = ByteArrayOutputStream()
            ImageIO.write(buffered, "png", out)
            return Clipped.Image(Picture(out.toByteArray(), "image/png"))
        }
        if (held.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = held.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File>
            return Clipped.Files(files.map { it.path })
        }
        if (held.isDataFlavorSupported(DataFlavor.stringFlavor)) return Clipped.Text(held.getTransferData(DataFlavor.stringFlavor) as String)
        return Clipped.Empty
    }

    override fun write(picture: Picture) {
        val image = ImageIO.read(ByteArrayInputStream(picture.bytes)) ?: throw OrknuxException("Java cannot read that picture (${picture.contentType}).")
        clipboard.setContents(object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any =
                if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
        }, null)
    }
}
