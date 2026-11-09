"""Nexos PDF — backend de conversión Word a PDF.

Usa LibreOffice en modo headless (soffice --convert-to pdf).
Endpoints:
  GET  /healthz  -> {"ok": true}
  POST /convert  -> recibe un archivo .doc/.docx/.odt/.rtf/.txt y
                    devuelve el PDF convertido.
"""
import asyncio
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

app = FastAPI(title="Nexos PDF")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["POST", "GET"],
    allow_headers=["*"],
)

EXTENSIONES = {".doc", ".docx", ".odt", ".rtf", ".txt"}
MAX_MB = 10
# LibreOffice es pesado en RAM; en el plan gratis de Render (512 MB)
# conviene no correr más de 2 conversiones a la vez.
SEMAFORO = asyncio.Semaphore(2)


@app.get("/healthz")
def salud():
    return {"ok": True, "soffice": shutil.which("soffice") is not None}


@app.post("/convert")
async def convertir(file: UploadFile = File(...)):
    nombre = Path(file.filename or "documento").name
    ext = Path(nombre).suffix.lower()
    if ext not in EXTENSIONES:
        raise HTTPException(
            status_code=400,
            detail=f"Formato no soportado: {ext or '(sin extensión)'}. Usa .doc, .docx, .odt, .rtf o .txt.",
        )
    if shutil.which("soffice") is None:
        raise HTTPException(status_code=500, detail="LibreOffice no está instalado en el servidor.")

    contenido = await file.read()
    if len(contenido) > MAX_MB * 1024 * 1024:
        raise HTTPException(status_code=400, detail=f"El archivo pasa de {MAX_MB} MB.")

    async with SEMAFORO:
        with tempfile.TemporaryDirectory(prefix="nexospdf-") as tmp:
            entrada = Path(tmp) / f"entrada{ext}"
            entrada.write_bytes(contenido)
            perfil = Path(tmp) / "perfil"
            perfil.mkdir()
            env = dict(os.environ)
            env["HOME"] = tmp
            try:
                proc = await asyncio.to_thread(
                    subprocess.run,
                    [
                        "soffice",
                        "--headless",
                        "--nologo",
                        "--nolockcheck",
                        f"-env:UserInstallation=file://{perfil}",
                        "--convert-to",
                        "pdf",
                        "--outdir",
                        tmp,
                        str(entrada),
                    ],
                    capture_output=True,
                    text=True,
                    timeout=120,
                    env=env,
                )
            except subprocess.TimeoutExpired:
                raise HTTPException(status_code=500, detail="La conversión tardó demasiado (más de 2 minutos).")
            pdf = Path(tmp) / f"entrada.pdf"
            if proc.returncode != 0 or not pdf.exists():
                detalle = (proc.stderr or proc.stdout or "").strip()[-300:]
                raise HTTPException(status_code=500, detail=f"No se pudo convertir el archivo. {detalle}")
            salida = Path(tempfile.gettempdir()) / f"nexospdf-{os.urandom(6).hex()}.pdf"
            shutil.copyfile(pdf, salida)
            nombre_pdf = Path(nombre).stem + ".pdf"
            return FileResponse(
                salida,
                media_type="application/pdf",
                filename=nombre_pdf,
                background=None,
            )
