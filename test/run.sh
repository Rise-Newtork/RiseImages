#!/bin/sh
# Runs the entrypoint against the fixtures with a fake java. Needs jq.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
entrypoint="$here/../entrypoint.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

failures=0
check() {
    if [ "$1" = "$2" ]; then
        echo "ok   $3"
    else
        echo "FAIL $3: expected '$2', got '$1'"
        failures=$((failures + 1))
    fi
}

run() {
    app="$work/app-$1"; shift
    mkdir -p "$app"
    log="$app.java.log"
    : > "$log"
    set +e
    env -i PATH="$here/bin:$PATH" FAKE_JAVA_LOG="$log" RISE_APP_DIR="$app" \
        RISE_DEPEND_MOUNT="$here/fixtures" "$@" sh "$entrypoint" > "$app.out" 2>&1
    status=$?
    set -e
}

# Stick game: its own files plus the shared mod, not the hub's.
run stick RISE_SERVER_TYPES=SERVER_TYPE_STICK_GAME RISE_GAME_PORT=5520 \
    RISE_SERVER_META='{"containerName":"x"}'
check "$status" 0 "stick game starts"
check "$(cat "$app/mods/core.jar")" core "mod copied"
check "$(cat "$app/mods/common.jar")" common "shared mod copied"
check "$([ -e "$app/mods/lobby.jar" ] && echo present || echo absent)" absent "other type's mod not copied"
check "$(cat "$app/universe/worlds/tiki/chunks/0.bin")" chunk "world copied under its id"
# A map's chunks and the skeleton they compose with belong to the asset pack the creator
# assembles and binds in, so neither is staged here.
check "$([ -e "$app/universe/worlds/sg_tiki" ] && echo present || echo absent)" absent "map chunks not staged"
check "$([ -e "$app/universe/worlds/arena.bson" ] && echo present || echo absent)" absent "skeleton not staged"
check "$(grep -c 'map pack component' "$app.out")" 2 "both pack components explained"
check "$(cat "$app/configs/arena/settings.json")" '{"arena":1}' "config copied with its structure"
check "$(cat "$app/config.json")" '{"Defaults":{"World":"tiki"}}' "root file copied to the root"
check "$(cat "$app/servermeta.json")" '{"containerName":"x"}' "servermeta.json written"
check "$(head -1 "$log")" "-jar" "no jvm flags by default"
check "$(sed -n 2p "$log")" "$here/fixtures/hytale/HytaleServer.jar" "jar from the volume"
check "$(sed -n 3,4p "$log" | tr '\n' ' ')" "--bind 0.0.0.0:5520 " "bind"
check "$(sed -n 5,6p "$log" | tr '\n' ' ')" "--assets $here/fixtures/hytale/Assets.zip " "assets"
check "$(sed -n 7,8p "$log" | tr '\n' ' ')" "--auth-mode insecure " "insecure by default"
check "$(sed -n 9p "$log")" "--disable-file-watcher" "default server flags"
check "$(sed -n 10p "$log")" "$app" "working directory"

# Flags: jvm before -jar, server flags after the fixed ones.
run flags RISE_SERVER_TYPES=STICK_GAME RISE_JVM_FLAGS="-Xmx2g -Xms1g" RISE_SERVER_FLAGS="--disable-sentry --allow-op" RISE_GAME_PORT=6000 RISE_AUTH_MODE=authenticated
check "$status" 0 "flags run starts"
check "$(sed -n 1,2p "$log" | tr '\n' ' ')" "-Xmx2g -Xms1g " "jvm flags first"
check "$(sed -n 6p "$log")" "0.0.0.0:6000" "game port"
check "$(sed -n 10p "$log")" "authenticated" "auth mode from the env"
check "$(sed -n 11,12p "$log" | tr '\n' ' ')" "--disable-sentry --allow-op " "server flags last"
check "$(cat "$app/mods/core.jar")" core "short type name accepted"

# Hub: only its own mod and the shared one.
run hub RISE_SERVER_TYPES=SERVER_TYPE_HUB
check "$status" 0 "hub starts"
check "$(find "$app/mods" -mindepth 1 -printf '%f\n' | sort | tr '\n' ' ')" "common.jar lobby.jar " "hub mods"
check "$([ -e "$app/universe" ] && echo present || echo absent)" absent "no world for hub"

# Refusals.
run notypes RISE_SERVER_TYPES=
check "$status" 1 "empty types refused"
check "$(grep -c 'RISE_SERVER_TYPES' "$app.out")" 1 "empty types explained"

run nomount RISE_SERVER_TYPES=HUB RISE_DEPEND_MOUNT="$work/nowhere"
check "$status" 1 "missing mount refused"

# An index from before map relations: no entry is a pack component, so a map stages as it used to.
legacy="$work/legacy-index"
mkdir -p "$legacy"
cp -R "$here/fixtures/." "$legacy/"
jq '.schemaVersion = 1 | del(.entries[].skeleton)' "$here/fixtures/index.json" > "$legacy/index.json"
run legacy RISE_SERVER_TYPES=STICK_GAME RISE_DEPEND_MOUNT="$legacy"
check "$status" 0 "schema 1 index still starts"
check "$(grep -c 'schema 1' "$app.out")" 1 "schema 1 warned about"
check "$(cat "$app/universe/worlds/sg_tiki/chunks/0.0.region.bin")" chunk "schema 1 stages a map as before"

# An index shape this image does not know at all.
future="$work/future-index"
mkdir -p "$future"
cp -R "$here/fixtures/." "$future/"
jq '.schemaVersion = 9' "$here/fixtures/index.json" > "$future/index.json"
run future RISE_SERVER_TYPES=STICK_GAME RISE_DEPEND_MOUNT="$future"
check "$status" 1 "unknown index schema refused"
check "$(grep -c "this image reads 1 or 2" "$app.out")" 1 "schema refusal explained"
check "$(grep -c 'starting Hytale' "$app.out")" 0 "server not started after a schema refusal"

# Two entries landing on the same path.
run collide RISE_SERVER_TYPES=STICK_GAME,HUB_PVP
check "$status" 1 "colliding root files refused"
check "$(grep -c 'already written' "$app.out")" 1 "collision explained"
check "$(grep -c 'starting Hytale' "$app.out")" 0 "server not started after a refusal"

echo
if [ "$failures" -eq 0 ]; then
    echo "all checks passed"
else
    echo "$failures check(s) failed"
    exit 1
fi
