FROM python:3.10-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    g++ \
    postgresql-client \
    libpq-dev \
    poppler-utils \
    tesseract-ocr \
    tesseract-ocr-eng \
    tesseract-ocr-vie \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Keep PyTorch in its own cached layer: other dependency changes must not reinstall it.
RUN pip install --upgrade pip \
    && pip install torch==2.2.2+cpu --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt /app/requirements.txt
RUN pip install -r /app/requirements.txt \
    && python -c "import torch; assert torch.version.cuda is None, 'Expected CPU-only PyTorch'"

COPY . /app

RUN chmod +x /app/scripts/run_api.sh /app/scripts/run_worker.sh /app/docker-entrypoint.sh

EXPOSE 8001
