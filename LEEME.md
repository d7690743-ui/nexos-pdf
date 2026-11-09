# Nexos PDF — v1.0

Página propia estilo iLovePDF, hecha para David el 2026-11-09.
Dos herramientas en la primera versión:

1. **Word a PDF** — sube un .doc/.docx y descarga el PDF. Necesita el
   backend (`backend/`, FastAPI + LibreOffice) desplegado, por ejemplo
   en Render gratis. Mientras `BACKEND_URL` esté vacío en
   `frontend/index.html`, la página avisa que falta configurar el servidor.
2. **Unir PDFs** — sube dos o más PDFs, ordénalos con las flechas y
   únelos en uno solo. Funciona 100% en el navegador con pdf-lib
   (vendido en `frontend/pdf-lib.min.js`, sin CDN), no sube nada a
   ningún servidor.

## Contenido

- `frontend/` — página estática: `index.html` + `pdf-lib.min.js`.
  Se puede abrir directo en el navegador o subir a cualquier hosting
  estático (Render Static Site, Vercel, GitHub Pages).
- `backend/` — FastAPI + LibreOffice (Dockerfile + `render.yaml`).
  Ver `backend/LEEME.md` para probarlo en local y desplegarlo en Render.

## Nombre

"Nexos PDF" es provisional, a juego con Nexos MX. Si David prefiere otro
nombre, se cambia en el `<title>` y el `<h1>` de `frontend/index.html`.

## Pendiente

- Desplegar el backend en Render y poner su URL en `BACKEND_URL`.
- Más herramientas en la 1.1 si David las pide: dividir PDF, comprimir,
  PDF a Word, firmar, etc.
