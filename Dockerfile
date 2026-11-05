# Coolify "Dockerfile" build pack ke liye — Python server (server/server.py)
FROM python:3.12-slim

WORKDIR /app

COPY server/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server/ .

ENV PORT=8080
EXPOSE 8080

CMD ["python", "server.py"]
