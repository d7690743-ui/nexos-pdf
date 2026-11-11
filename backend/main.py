"""Nexos PDF — backend de conversión Word a PDF + subida de archivos.

Usa LibreOffice en modo headless (soffice --convert-to pdf).
Endpoints:
  GET  /healthz  -> {"ok": true}
  POST /convert  -> recibe un archivo .doc/.docx/.odt/.rtf/.txt y
                    devuelve el PDF convertido.
  GET  /         -> página para subir archivos
  POST /upload   -> recibe varios archivos y devuelve una URL por cada uno
  GET  /f/{token} -> descarga un archivo subido
"""
import asyncio
import mimetypes
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse

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

# Subida de archivos. Ojo: en el plan gratis de Render el disco es
# efímero, los archivos se borran cuando el servicio se reinicia.
SUBIDAS = Path(os.environ.get("UPLOAD_DIR", "/tmp/nexos-subidas"))
SUBIDAS.mkdir(parents=True, exist_ok=True)
MAX_SUBIDAS = 10
MAX_SUBIDA_MB = 25

PAGINA_SUBIR = """<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Subir archivos — Nexos</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; background: #f2f5f3; color: #1c2420; min-height: 100vh; }
  header { background: #14532d; color: #fff; padding: 18px 16px; text-align: center; }
  header h1 { font-size: 1.4rem; }
  header p { font-size: .88rem; opacity: .85; margin-top: 4px; }
  main { max-width: 640px; margin: 0 auto; padding: 16px 12px 40px; }
  .card { background: #fff; border-radius: 12px; padding: 18px 16px; box-shadow: 0 2px 8px rgba(0,0,0,.08); }
  .drop { border: 2px dashed #9ca3af; border-radius: 10px; padding: 26px 12px; text-align: center; color: #4b5563; font-size: .95rem; cursor: pointer; }
  .drop:hover, .drop.over { border-color: #14532d; color: #14532d; }
  input[type=file] { display: none; }
  .btn { display: block; width: 100%; padding: 13px; margin-top: 14px; font-size: 1.02rem; font-weight: 700; border: none; border-radius: 10px; background: #16a34a; color: #fff; cursor: pointer; }
  .btn:disabled { background: #9ca3af; cursor: default; }
  .status { margin-top: 12px; font-size: .93rem; min-height: 1.2em; }
  .status.ok { color: #15803d; }
  .status.err { color: #b91c1c; }
  .res { list-style: none; margin-top: 12px; }
  .res li { padding: 10px 6px; border-bottom: 1px solid #e5e7eb; font-size: .9rem; }
  .res .nombre { font-weight: 600; word-break: break-all; }
  .res .fila { display: flex; gap: 8px; margin-top: 6px; }
  .res a.abrir { flex: 1; text-align: center; padding: 8px; background: #14532d; color: #fff; font-weight: 700; text-decoration: none; border-radius: 8px; font-size: .88rem; }
  .res button.copiar { flex: 1; padding: 8px; border: 1px solid #14532d; background: #fff; color: #14532d; font-weight: 700; border-radius: 8px; font-size: .88rem; cursor: pointer; }
  .note { margin-top: 14px; font-size: .82rem; color: #6b7280; text-align: center; }
</style>
</head>
<body>
<header>
  <h1>Subir archivos</h1>
  <p>Elige varios archivos y te doy una URL para cada uno</p>
</header>
<main>
  <div class="card">
    <label class="drop" id="drop" for="input">Toca aqu&iacute; o arrastra tus archivos</label>
    <input type="file" id="input" multiple>
    <div class="status" id="status"></div>
    <button class="btn" id="btn" disabled>Subir</button>
    <ul class="res" id="res"></ul>
    <p class="note">M&aacute;ximo 10 archivos, 25 MB cada uno. Los archivos se borran cuando el servidor se reinicia (plan gratis de Render), as&iacute; que pasa la URL pronto.</p>
  </div>
</main>
<script>
const $ = id => document.getElementById(id);
let archivos = [];
$("input").addEventListener("change", e => { elegir(e.target.files); e.target.value = ""; });
["dragover","dragenter"].forEach(ev => $("drop").addEventListener(ev, e => { e.preventDefault(); $("drop").classList.add("over"); }));
["dragleave","drop"].forEach(ev => $("drop").addEventListener(ev, e => { e.preventDefault(); $("drop").classList.remove("over"); }));
$("drop").addEventListener("drop", e => elegir(e.dataTransfer.files));
function elegir(files) {
  for (const f of files) { if (archivos.length < 10) archivos.push(f); }
  $("drop").textContent = archivos.length ? archivos.length + " archivo(s) elegido(s)" : "Toca aquí o arrastra tus archivos";
  $("btn").disabled = !archivos.length;
  $("btn").textContent = archivos.length > 1 ? "Subir " + archivos.length + " archivos" : "Subir";
  $("status").textContent = "";
  $("status").className = "status";
}
$("btn").onclick = async () => {
  if (!archivos.length) return;
  $("btn").disabled = true;
  $("btn").textContent = "Subiendo…";
  $("status").textContent = "Subiendo " + archivos.length + " archivo(s)…";
  $("status").className = "status";
  $("res").innerHTML = "";
  try {
    const form = new FormData();
    for (const f of archivos) form.append("files", f, f.name);
    const r = await fetch("upload", { method: "POST", body: form });
    if (!r.ok) throw new Error("El servidor respondió " + r.status);
    const data = await r.json();
    for (const a of data.archivos) {
      const url = new URL(a.url, location.href).href;
      const li = document.createElement("li");
      const n = document.createElement("div");
      n.className = "nombre";
      n.textContent = a.nombre + " (" + Math.round(a.bytes / 1024) + " KB)";
      const fila = document.createElement("div");
      fila.className = "fila";
      const abrir = document.createElement("a");
      abrir.className = "abrir";
      abrir.href = url;
      abrir.target = "_blank";
      abrir.rel = "noopener";
      abrir.textContent = "Abrir";
      const copiar = document.createElement("button");
      copiar.className = "copiar";
      copiar.textContent = "Copiar URL";
      copiar.onclick = async () => {
        try { await navigator.clipboard.writeText(url); copiar.textContent = "¡Copiada!"; }
        catch (e) { prompt("Copia la URL:", url); }
        setTimeout(() => copiar.textContent = "Copiar URL", 2000);
      };
      fila.append(abrir, copiar);
      li.append(n, fila);
      $("res").appendChild(li);
    }
    $("status").textContent = "Listo: " + data.archivos.length + " archivo(s) subido(s).";
    $("status").className = "status ok";
    archivos = [];
    $("drop").textContent = "Toca aquí o arrastra tus archivos";
  } catch (err) {
    $("status").textContent = "No se pudo subir: " + err.message;
    $("status").className = "status err";
  } finally {
    $("btn").disabled = !archivos.length;
    $("btn").textContent = "Subir";
  }
};
</script>
</body>
</html>"""


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


@app.get("/", response_class=HTMLResponse)
def pagina_subir():
    return PAGINA_SUBIR


@app.post("/upload")
async def subir(files: list[UploadFile] = File(...)):
    if not files:
        raise HTTPException(status_code=400, detail="No llegaron archivos.")
    if len(files) > MAX_SUBIDAS:
        raise HTTPException(status_code=400, detail=f"Máximo {MAX_SUBIDAS} archivos por subida.")
    limite = MAX_SUBIDA_MB * 1024 * 1024
    respuesta = []
    for up in files:
        original = Path(up.filename or "archivo").name or "archivo"
        seguro = re.sub(r"[^\w.\-]+", "_", original, flags=re.UNICODE).strip("._")[:60] or "archivo"
        token = f"{os.urandom(8).hex()}-{seguro}"
        destino = SUBIDAS / token
        tam = 0
        try:
            with open(destino, "wb") as f:
                while True:
                    trozo = await up.read(1024 * 1024)
                    if not trozo:
                        break
                    tam += len(trozo)
                    if tam > limite:
                        raise HTTPException(
                            status_code=400,
                            detail=f"{original} pasa de {MAX_SUBIDA_MB} MB.",
                        )
                    f.write(trozo)
        except HTTPException:
            destino.unlink(missing_ok=True)
            raise
        finally:
            await up.close()
        respuesta.append({"nombre": original, "url": f"/f/{token}", "bytes": tam})
    return {"archivos": respuesta}


@app.get("/f/{token}")
def descargar(token: str):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._\-]*", token):
        raise HTTPException(status_code=404, detail="No existe.")
    base = SUBIDAS.resolve()
    ruta = (base / token).resolve()
    if ruta.parent != base or not ruta.is_file():
        raise HTTPException(status_code=404, detail="No existe.")
    tipo, _ = mimetypes.guess_type(ruta.name)
    return FileResponse(ruta, media_type=tipo or "application/octet-stream")
