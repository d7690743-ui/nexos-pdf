FROM python:3.11-slim

# LibreOffice Writer en modo headless para convertir Word a PDF.
# --no-install-recommends para no inflar la imagen de más.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libreoffice-writer \
        fonts-liberation \
        fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py .

# Render pone el puerto en $PORT (10000 por defecto).
CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-8000}"]
