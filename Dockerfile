FROM eclipse-temurin:25-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends jq \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 hytale \
    && useradd --system --uid 10001 --gid hytale --home-dir /app --shell /usr/sbin/nologin hytale \
    && mkdir /app \
    && chown hytale:hytale /app

COPY entrypoint.sh /usr/local/bin/entrypoint

ENV RISE_DEPEND_MOUNT=/dependfiles \
    RISE_GAME_PORT=5520 \
    RISE_AUTH_MODE=insecure \
    RISE_SERVER_FLAGS=--disable-file-watcher

WORKDIR /app
USER 10001:10001
ENTRYPOINT ["entrypoint"]
