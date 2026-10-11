FROM clojure:temurin-21-tools-deps-bookworm-slim

RUN command -v git >/dev/null || \
    (apt-get update && apt-get install -y --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/*)

WORKDIR /app

COPY deps.edn .
RUN clojure -P -X:test

COPY . .

CMD ["clojure", "-X:test"]
