package com.nexos.pdf

import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Convierte un .docx a HTML dentro del teléfono, sin servidor ni dependencias.
 *
 *  Cubre lo que traen los documentos normales: párrafos, títulos, negritas,
 *  cursivas, subrayado, listas, tablas, enlaces e imágenes (se incrustan en
 *  base64, así que un código QR sale intacto). Límites honestos: las imágenes
 *  EMF/WMF (vectoriales viejas de Office) se saltan, igual que macros y
 *  objetos OLE. */
object DocxConverter {

    fun convertir(entrada: InputStream): String {
        var documentXml: ByteArray? = null
        var relsXml: ByteArray? = null
        var numberingXml: ByteArray? = null
        val medios = mutableMapOf<String, ByteArray>()
        ZipInputStream(entrada).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    when (e.name) {
                        "word/document.xml" -> documentXml = zip.readBytes()
                        "word/_rels/document.xml.rels" -> relsXml = zip.readBytes()
                        "word/numbering.xml" -> numberingXml = zip.readBytes()
                        else -> if (e.name.startsWith("word/media/")) {
                            medios[e.name] = zip.readBytes()
                        }
                    }
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        val doc = documentXml
            ?: throw IllegalArgumentException("No es un .docx válido (falta word/document.xml)")
        val ctx = Contexto(relsXml, numberingXml, medios)
        val cuerpo = StringBuilder()
        val p = nuevoParser(doc)
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                when (p.name) {
                    "w:p" -> cuerpo.append(parrafo(p, ctx))
                    "w:tbl" -> cuerpo.append(tabla(p, ctx))
                }
            }
            e = p.next()
        }
        return HTML_INICIO + cuerpo.toString() + HTML_FIN
    }

    /** Estado compartido: relaciones, numeración, imágenes y contadores. */
    private class Contexto(
        relsXml: ByteArray?,
        numberingXml: ByteArray?,
        val medios: Map<String, ByteArray>
    ) {
        val imagenes = mutableMapOf<String, String>() // rId -> ruta en el zip
        val enlaces = mutableMapOf<String, String>() // rId -> URL
        val numFmt = mutableMapOf<String, Map<String, String>>() // numId -> (ilvl -> fmt)
        private val contadores = mutableMapOf<String, Int>()

        init {
            try {
                leerRels(relsXml)
            } catch (_: Exception) {
            }
            try {
                leerNumbering(numberingXml)
            } catch (_: Exception) {
            }
        }

        private fun leerRels(xml: ByteArray?) {
            if (xml == null) return
            val p = nuevoParser(xml)
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.getAttributeValue(null, "Id")
                    val tipo = p.getAttributeValue(null, "Type") ?: ""
                    val destino = p.getAttributeValue(null, "Target") ?: ""
                    if (id != null && destino.isNotEmpty()) {
                        when {
                            "hyperlink" in tipo -> enlaces[id] = destino
                            "image" in tipo -> imagenes[id] = normalizarRuta(destino)
                        }
                    }
                }
                e = p.next()
            }
        }

        private fun leerNumbering(xml: ByteArray?) {
            if (xml == null) return
            val abstractos = mutableMapOf<String, MutableMap<String, String>>()
            val numAbs = mutableMapOf<String, String>()
            val p = nuevoParser(xml)
            var absId: String? = null
            var lvl: String? = null
            var numId: String? = null
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                when (e) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "w:abstractNum" -> absId = p.getAttributeValue(null, "w:abstractNumId")
                        "w:lvl" -> lvl = p.getAttributeValue(null, "w:ilvl")
                        "w:numFmt" -> {
                            val fmt = p.getAttributeValue(null, "w:val")
                            if (absId != null && fmt != null) {
                                abstractos.getOrPut(absId!!) { mutableMapOf() }[lvl ?: "0"] = fmt
                            }
                        }
                        "w:num" -> numId = p.getAttributeValue(null, "w:numId")
                        "w:abstractNumId" -> {
                            val a = p.getAttributeValue(null, "w:val")
                            if (numId != null && a != null) numAbs[numId!!] = a
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "w:abstractNum" -> {
                            absId = null
                            lvl = null
                        }
                        "w:lvl" -> lvl = null
                        "w:num" -> numId = null
                    }
                }
                e = p.next()
            }
            for ((n, a) in numAbs) abstractos[a]?.let { numFmt[n] = it }
        }

        fun fmt(numId: String, ilvl: String): String =
            numFmt[numId]?.get(ilvl) ?: numFmt[numId]?.get("0") ?: "bullet"

        fun siguiente(numId: String, ilvl: String): Int {
            val k = "$numId:$ilvl"
            val n = (contadores[k] ?: 0) + 1
            contadores[k] = n
            return n
        }
    }

    private fun parrafo(p: XmlPullParser, ctx: Contexto): String {
        var estilo: String? = null
        var align: String? = null
        var numId: String? = null
        var ilvl = "0"
        val html = StringBuilder()
        var e = p.next()
        while (!(e == XmlPullParser.END_TAG && p.name == "w:p")) {
            if (e == XmlPullParser.START_TAG) {
                when (p.name) {
                    "w:r" -> html.append(run(p, ctx))
                    "w:hyperlink" -> {
                        val href = ctx.enlaces[p.getAttributeValue(null, "r:id")] ?: ""
                        val inner = StringBuilder()
                        var e2 = p.next()
                        while (!(e2 == XmlPullParser.END_TAG && p.name == "w:hyperlink")) {
                            if (e2 == XmlPullParser.START_TAG && p.name == "w:r") {
                                inner.append(run(p, ctx))
                            }
                            e2 = p.next()
                        }
                        val texto = inner.toString()
                        if (texto.isNotEmpty()) {
                            html.append(if (href.isNotEmpty()) "<a href=\"${esc(href)}\">$texto</a>" else texto)
                        }
                    }
                    "w:del", "w:moveFrom" -> saltar(p)
                    "w:pStyle" -> estilo = p.getAttributeValue(null, "w:val")
                    "w:jc" -> align = p.getAttributeValue(null, "w:val")
                    "w:numId" -> numId = p.getAttributeValue(null, "w:val")
                    "w:ilvl" -> p.getAttributeValue(null, "w:val")?.let { ilvl = it }
                }
            }
            e = p.next()
        }
        val contenido = html.toString()
        if (contenido.isBlank()) return "<p>&nbsp;</p>"
        val tag = when (estilo) {
            "Title", "Heading1" -> "h1"
            "Heading2" -> "h2"
            "Heading3" -> "h3"
            "Heading4" -> "h4"
            "Heading5" -> "h5"
            "Heading6" -> "h6"
            else -> "p"
        }
        val css = StringBuilder()
        align?.let {
            val a = when (it) {
                "center" -> "center"
                "right", "end" -> "right"
                "both", "distribute" -> "justify"
                else -> "left"
            }
            css.append("text-align:$a;")
        }
        var prefijo = ""
        if (tag == "p" && numId != null) {
            val f = ctx.fmt(numId!!, ilvl)
            val nivel = ilvl.toIntOrNull() ?: 0
            css.append("margin-left:${24 + nivel * 24}px;")
            prefijo = if (f == "bullet" || f == "none") "• " else "${ctx.siguiente(numId!!, ilvl)}. "
        }
        val style = if (css.isNotEmpty()) " style=\"$css\"" else ""
        return "<$tag$style>$prefijo$contenido</$tag>"
    }

    private fun run(p: XmlPullParser, ctx: Contexto): String {
        var b = false
        var i = false
        var u = false
        var s = false
        var tam: String? = null
        var color: String? = null
        var fuente: String? = null
        val html = StringBuilder()
        var e = p.next()
        while (!(e == XmlPullParser.END_TAG && p.name == "w:r")) {
            if (e == XmlPullParser.START_TAG) {
                when (p.name) {
                    "w:b" -> b = p.getAttributeValue(null, "w:val") != "false"
                    "w:i" -> i = p.getAttributeValue(null, "w:val") != "false"
                    "w:u" -> u = p.getAttributeValue(null, "w:val") != "none"
                    "w:strike", "w:dstrike" -> s = true
                    "w:sz" -> p.getAttributeValue(null, "w:val")?.toIntOrNull()?.let {
                        tam = "font-size:${(it * 2 / 3).coerceAtLeast(6)}px;"
                    }
                    "w:color" -> p.getAttributeValue(null, "w:val")?.let {
                        if (it != "auto" && it.matches(Regex("[0-9A-Fa-f]{6}"))) color = "color:#$it;"
                    }
                    "w:rFonts" -> p.getAttributeValue(null, "w:ascii")?.let {
                        if (it.matches(Regex("[A-Za-z0-9 \\-]+"))) fuente = "font-family:'$it',sans-serif;"
                    }
                    "w:t" -> html.append(esc(p.nextText()))
                    "w:tab" -> html.append("&nbsp;&nbsp;")
                    "w:br", "w:cr" -> html.append("<br/>")
                    "w:noBreakHyphen" -> html.append("&#8209;")
                    "w:drawing" -> imagenDrawing(p, ctx)?.let { html.append(it) }
                    "w:pict" -> imagenPict(p, ctx)?.let { html.append(it) }
                }
            }
            e = p.next()
        }
        var out = html.toString()
        if (out.isEmpty()) return ""
        if (b) out = "<b>$out</b>"
        if (i) out = "<i>$out</i>"
        if (u) out = "<u>$out</u>"
        if (s) out = "<s>$out</s>"
        val css = "${tam ?: ""}${color ?: ""}${fuente ?: ""}"
        return if (css.isNotEmpty()) "<span style=\"$css\">$out</span>" else out
    }

    private fun imagenDrawing(p: XmlPullParser, ctx: Contexto): String? {
        var id: String? = null
        var ancho = 0
        var alto = 0
        var e = p.next()
        while (!(e == XmlPullParser.END_TAG && p.name == "w:drawing")) {
            if (e == XmlPullParser.START_TAG) {
                when (p.name) {
                    "a:blip" -> p.getAttributeValue(null, "r:embed")?.let { id = it }
                    "wp:extent" -> {
                        val cx = p.getAttributeValue(null, "cx")?.toLongOrNull() ?: 0L
                        val cy = p.getAttributeValue(null, "cy")?.toLongOrNull() ?: 0L
                        if (cx > 0) ancho = (cx * 96 / 914400).toInt()
                        if (cy > 0) alto = (cy * 96 / 914400).toInt()
                    }
                }
            }
            e = p.next()
        }
        return img(id, ancho, alto, ctx)
    }

    private fun imagenPict(p: XmlPullParser, ctx: Contexto): String? {
        var id: String? = null
        var ancho = 0
        var alto = 0
        var e = p.next()
        while (!(e == XmlPullParser.END_TAG && p.name == "w:pict")) {
            if (e == XmlPullParser.START_TAG) {
                when (p.name) {
                    "v:imagedata" -> p.getAttributeValue(null, "r:id")?.let { id = it }
                    "v:shape" -> p.getAttributeValue(null, "style")?.let { st ->
                        Regex("width:([\\d.]+)pt").find(st)?.let {
                            ancho = ((it.groupValues[1].toDoubleOrNull() ?: 0.0) * 96 / 72).toInt()
                        }
                        Regex("height:([\\d.]+)pt").find(st)?.let {
                            alto = ((it.groupValues[1].toDoubleOrNull() ?: 0.0) * 96 / 72).toInt()
                        }
                    }
                }
            }
            e = p.next()
        }
        return img(id, ancho, alto, ctx)
    }

    private fun img(id: String?, ancho: Int, alto: Int, ctx: Contexto): String? {
        if (id == null) return null
        val ruta = ctx.imagenes[id] ?: return null
        val bytes = ctx.medios[ruta] ?: return null
        val mime = when (ruta.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "tif", "tiff" -> "image/tiff"
            "svg" -> "image/svg+xml"
            else -> return null // EMF/WMF no los abre el WebView
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val dim = (if (ancho > 0) " width=\"$ancho\"" else "") +
            (if (alto > 0) " height=\"$alto\"" else "")
        return "<img src=\"data:$mime;base64,$b64\"$dim/>"
    }

    private fun tabla(p: XmlPullParser, ctx: Contexto): String {
        val filas = StringBuilder()
        var e = p.next()
        while (!(e == XmlPullParser.END_TAG && p.name == "w:tbl")) {
            if (e == XmlPullParser.START_TAG && p.name == "w:tr") {
                val celdas = StringBuilder()
                var e2 = p.next()
                while (!(e2 == XmlPullParser.END_TAG && p.name == "w:tr")) {
                    if (e2 == XmlPullParser.START_TAG && p.name == "w:tc") {
                        celdas.append(celda(p, ctx))
                    }
                    e2 = p.next()
                }
                filas.append("<tr>$celdas</tr>")
            }
            e = p.next()
        }
        return "<table>$filas</table>"
    }

    private fun celda(p: XmlPullParser, ctx: Contexto): String {
        var colspan = 1
        var fondo: String? = null
        val html = StringBuilder()
        var e = p.next()
        while (!(e == XmlPullParser.END_TAG && p.name == "w:tc")) {
            if (e == XmlPullParser.START_TAG) {
                when (p.name) {
                    "w:p" -> html.append(parrafo(p, ctx))
                    "w:tbl" -> html.append(tabla(p, ctx))
                    "w:gridSpan" -> p.getAttributeValue(null, "w:val")?.toIntOrNull()?.let { colspan = it }
                    "w:shd" -> p.getAttributeValue(null, "w:fill")?.let {
                        if (it != "auto" && it.matches(Regex("[0-9A-Fa-f]{6}"))) fondo = it
                    }
                }
            }
            e = p.next()
        }
        val attrs = (if (colspan > 1) " colspan=\"$colspan\"" else "") +
            (if (fondo != null) " style=\"background:#$fondo;\"" else "")
        return "<td$attrs>$html</td>"
    }

    /** Salta un subárbol completo (posicionado en su START_TAG). */
    private fun saltar(p: XmlPullParser) {
        val nombre = p.name
        var prof = 1
        while (prof > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> if (p.name == nombre) prof++
                XmlPullParser.END_TAG -> if (p.name == nombre) prof--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val HTML_INICIO = """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;color:#000;background:#fff;max-width:800px;margin:0 auto;padding:16px;line-height:1.4}
p{margin:0 0 8px}h1{font-size:24px}h2{font-size:20px}h3{font-size:17px}
table{border-collapse:collapse;width:100%;margin:8px 0}
td{border:1px solid #888;padding:4px 6px;vertical-align:top}
img{max-width:100%;height:auto}a{color:#1155cc}</style></head><body>"""
    private const val HTML_FIN = "</body></html>"
}

/** Parser XML sin namespaces (las etiquetas llegan como "w:p"). */
private fun nuevoParser(xml: ByteArray): XmlPullParser {
    val f = XmlPullParserFactory.newInstance()
    f.isNamespaceAware = false
    return f.newPullParser().apply { setInput(xml.inputStream(), "UTF-8") }
}

/** Normaliza un Target de .rels a ruta dentro del zip ("media/x.png" -> "word/media/x.png"). */
private fun normalizarRuta(t: String): String {
    val s = t.removePrefix("/")
    return if (s.startsWith("word/")) s else "word/$s"
}
