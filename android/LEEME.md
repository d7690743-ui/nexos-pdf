# Nexos PDF — app Android (APK)

WebView con la página local (`app/src/main/assets/index.html` + `pdf-lib.min.js`).
Paquete `com.nexos.pdf`, versión 1.0 (versionCode 1), minSdk 24, targetSdk 35.

## Lo que hace la app (además de la página)

- **Selector de archivos:** los `<input type="file">` de la página abren el
  selector del sistema gracias a `onShowFileChooser` en `MainActivity.kt`.
  Para unir PDFs permite elegir varios a la vez.
- **Descargas:** los botones de descarga usan el puente JS `Android.descargar`
  (ver `descargarArchivo` en `index.html`). En Android 10+ el PDF se guarda
  en **Descargas/NexosPDF** sin permisos, con un Toast de confirmación.
  En Android 9 o anterior va a los archivos de la app.
- **Word a PDF:** necesita el backend desplegado. Mientras `BACKEND_URL`
  esté vacío en `index.html`, la página avisa que falta configurar el
  servidor. Cuando el backend esté en Render, hay que poner su URL en
  `index.html` (en `frontend/` y en `assets/`) y volver a compilar.

## Compilar

El workflow `.github/workflows/build-apk.yml` compila en GitHub Actions
con `gradle assembleDebug` (carpeta `android/`) y sube el artifact
`app-debug`. No se compila en local aquí (sin Android SDK), igual que
Terminal Ligera 2.10 y Nexos Studio 1.0.

La única dependencia externa es `androidx.appcompat:appcompat:1.7.0`
(para el selector de archivos). Todo lo demás es WebView del sistema.
