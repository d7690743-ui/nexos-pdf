package com.nexos.pdf

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Nexos PDF 1.0: WebView con la página local (assets/index.html).
 *  - Los inputs de archivo abren el selector del sistema (onShowFileChooser).
 *  - Las descargas usan el puente JS "Android.descargar": en Android 10+
 *    se guardan en Descargas/NexosPDF sin permisos; en versiones viejas
 *    van a los archivos de la app (Download). */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        webView.loadUrl("file:///android_asset/index.html")
        setContentView(webView)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
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
