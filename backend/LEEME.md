# Nexos PDF — backend (Word a PDF)

Convierte archivos de Word a PDF con LibreOffice en modo headless.
Lo usa el frontend (`../frontend/index.html`) cuando `BACKEND_URL` apunta aquí.

## Probarlo en local

```bash
cd backend
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

Probar la conversión:

```bash
curl -F "file=@prueba.docx" http://localhost:8000/convert -o prueba.pdf
curl http://localhost:8000/healthz
```

Necesitas LibreOffice instalado (`soffice` en el PATH). Límite: 10 MB por archivo,
máximo 2 conversiones a la vez (para no reventar la RAM del plan gratis de Render).

## Desplegarlo en Render (gratis)

Opción A — con el blueprint (render.yaml en la raíz del repo):
1. Sube todo el repo `nexos-pdf` a GitHub.
2. En Render: New → Blueprint → conecta el repo.
3. Render crea el servicio `nexos-pdf` con el Dockerfile de `backend/`.
   El primer build tarda varios minutos porque instala LibreOffice.

Opción B — manual:
1. Sube el repo a GitHub.
2. En Render: New → Web Service → conecta el repo.
3. Root Directory: `backend`. Environment: Docker. Plan: Free.
4. Deploy. Cuando esté en verde, copia la URL, por ejemplo
   `https://nexos-pdf.onrender.com`.

Después pon esa URL en `frontend/index.html`, en la línea:
`const BACKEND_URL = "https://nexos-pdf.onrender.com";`

Ojo: en el plan gratis Render duerme el servicio si nadie lo usa, así que
la primera conversión del día puede tardar ~1 minuto en despertar. Las
siguientes ya son rápidas (unos segundos).
