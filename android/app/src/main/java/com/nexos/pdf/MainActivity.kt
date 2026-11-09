package com.nexos.pdf

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Nexos PDF 1.1: WebView con la página local (assets/index.html) + conversión
 *  Word→PDF en el teléfono, sin servidor.
 *  - "Elegir Word (.docx)" abre el selector del sistema, convierte el archivo
 *    a HTML con DocxConverter y lo muestra en el WebView como vista previa.
 *  - "Guardar como PDF" imprime esa vista con el diálogo del sistema
 *    (ahí se elige "Guardar como PDF", tamaño carta).
 *  - Lo de la 1.0 sigue igual: file chooser para la página y puente JS
 *    "Android.descargar" para guardar PDFs en Descargas/NexosPDF. */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var botonGuardar: Button
    private var nombreDoc: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val chooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        var uris: Array<Uri>? = null
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                val clip = data.clipData
                uris = if (clip != null && clip.itemCount > 0) {
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            }
        }
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private val docxLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) convertirDocx(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val botonElegir = Button(this).apply { text = "Elegir Word (.docx)" }
        botonGuardar = Button(this).apply {
            text = "Guardar como PDF"
            isEnabled = false
        }
        botonElegir.setOnClickListener {
            docxLauncher.launch(
                arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            )
        }
        botonGuardar.setOnClickListener { guardarPdf() }
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 8)
            addView(
                botonElegir,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                botonGuardar,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.addJavascriptInterface(PuenteDescarga(this), "Android")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                chooserLauncher.launch(params.createIntent())
                return true
            }
        }
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                barra,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                webView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        webView.loadUrl("file:///android_asset/index.html")
        setContentView(raiz)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /** Convierte el .docx a HTML en un hilo y lo muestra como vista previa. */
    private fun convertirDocx(uri: Uri) {
        Toast.makeText(this, "Convirtiendo…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val nombre = contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else null
                } ?: "documento"
                val html = contentResolver.openInputStream(uri)?.use { DocxConverter.convertir(it) }
                    ?: throw IllegalStateException("No se pudo abrir el archivo")
                runOnUiThread {
                    nombreDoc = nombre.substringBeforeLast('.')
                    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    botonGuardar.isEnabled = true
                    Toast.makeText(
                        this, "Listo: revisa y pulsa «Guardar como PDF»", Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "No se pudo convertir: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Imprime la vista previa con el diálogo del sistema (tamaño carta). */
    private fun guardarPdf() {
        val trabajo = "${nombreDoc ?: "documento"}.pdf"
        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
            .setResolution(PrintAttributes.Resolution("pdf", "PDF", 300, 300))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        pm.print(trabajo, webView.createPrintDocumentAdapter(trabajo), attrs)
        Toast.makeText(this, "Elige «Guardar como PDF» para descargar", Toast.LENGTH_LONG).show()
    }

    /** Recibe un PDF en base64 desde la página y lo guarda en el teléfono. */
    private class PuenteDescarga(private val context: Context) {
        @JavascriptInterface
        fun descargar(base64: String, nombre: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val mensaje: String
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val valores = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, nombre)
                        put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/NexosPDF"
                        )
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores
                    ) ?: throw IllegalStateException("No se pudo crear el archivo en Descargas")
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    mensaje = "Guardado en Descargas/NexosPDF: $nombre"
                } else {
                    val carpeta = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                        "NexosPDF"
                    )
                    if (!carpeta.exists()) carpeta.mkdirs()
                    File(carpeta, nombre).writeBytes(bytes)
                    mensaje = "Guardado en archivos de la app: $nombre"
                }
                avisar(mensaje)
            } catch (e: Exception) {
                avisar("No se pudo guardar: ${e.message}")
            }
        }

        private fun avisar(texto: String) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, texto, Toast.LENGTH_LONG).show()
            }
        }
    }
}
