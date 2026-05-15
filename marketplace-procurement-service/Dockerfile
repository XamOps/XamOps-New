FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

ENV FLASK_ENV=local
ENV PORT=5003
ENV WORKERS=2
ENV THREADS=4
ENV TIMEOUT=60

EXPOSE 5003

CMD gunicorn \
    --workers $WORKERS \
    --threads $THREADS \
    --timeout $TIMEOUT \
    --bind 0.0.0.0:$PORT \
    --access-logfile - \
    --error-logfile - \
    app:app
