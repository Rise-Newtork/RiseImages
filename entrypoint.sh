#!/bin/sh
set -eu

mount="${RISE_DEPEND_MOUNT:-/dependfiles}"
app="${RISE_APP_DIR:-/app}"
types="${RISE_SERVER_TYPES:-}"
game_port="${RISE_GAME_PORT:-5520}"
jvm_flags="${RISE_JVM_FLAGS:-}"
server_flags="${RISE_SERVER_FLAGS:---disable-file-watcher}"
auth_mode="${RISE_AUTH_MODE:-insecure}"

index="$mount/index.json"
jar="$mount/hytale/HytaleServer.jar"
assets="$mount/hytale/Assets.zip"
sentry_agent=/opt/rise/sentry-agent.jar

log() { echo "entrypoint: $*"; }
fail() { echo "entrypoint: $*" >&2; exit 1; }

[ -n "$types" ] || fail "RISE_SERVER_TYPES is not set"
[ -d "$mount" ] || fail "$mount is not mounted"
[ -f "$index" ] || fail "$index is missing; DependSync has not synced this node"
[ -f "$jar" ] || fail "$jar is missing; DependSync has not installed Hytale"
[ -f "$assets" ] || fail "$assets is missing; DependSync has not installed Hytale"
[ -w "$app" ] || fail "$app is not writable"

# The shapes of index.json this image knows. 1 is from before an entry carried the skeleton its
# chunks compose with, so on one of those no entry is a map pack component and maps stage as they
# always did. Anything else is refused rather than half-read.
schema=$(jq -r '.schemaVersion // empty' "$index")
case "$schema" in
    2) ;;
    1) log "warning: $index is schema 1, from a DependSync before map relations; any map entry will be staged into universe/worlds" ;;
    *) fail "$index is schema '$schema'; this image reads 1 or 2, so update the image or DependSync" ;;
esac

# Accepts STICK_GAME as well as SERVER_TYPE_STICK_GAME; compared case-insensitively
# because the protocol spells one type SERVER_TYPE_DUEL_2v2.
wanted=$(printf '%s' "$types" | tr ',' '\n' | sed 's/^ *//;s/ *$//;/^$/d' \
    | tr '[:lower:]' '[:upper:]' | sed 's/^\(SERVER_TYPE_\)\{0,1\}/SERVER_TYPE_/' | jq -R . | jq -sc .)

# The last column is what to do with the entry: a map's chunks (it names a skeleton) and the
# skeleton itself (something names it) are pack components, which the creator assembles into an
# asset pack and binds in read-only. Nothing reads universe/worlds for a match.
selected=$(jq -r --argjson wanted "$wanted" '
    [.entries[] | .skeleton // empty] as $skeletons
    | .entries[]
    | select(any(.serverTypes[]; . == "SERVER_TYPE_UNSPECIFIED" or (ascii_upcase | IN($wanted[]))))
    | [.category, .id, .objectKey, (.unpacked // false),
       (if .skeleton != null or (.id | IN($skeletons[])) then "pack" else "stage" end)]
    | @tsv' "$index")

[ -n "$selected" ] || log "warning: no entries in $index are tagged for $types"

written=""
while IFS="$(printf '\t')" read -r category id key unpacked role; do
    [ -n "$category" ] || continue

    if [ "$role" = "pack" ]; then
        log "$category $id -> not staged; it is a map pack component"
        continue
    fi

    case "$category" in
        mods) prefix="mods/"; dest="$app/mods" ;;
        worldFiles) prefix="worlds/"; dest="$app/universe/worlds" ;;
        configs) prefix="configs/"; dest="$app/configs" ;;
        root) prefix="root/"; dest="$app" ;;
        *) fail "entry $id has unknown category '$category'" ;;
    esac

    if [ "$unpacked" = "true" ]; then
        relative="${key%/*}/$id"
    else
        relative="$key"
    fi

    case "$relative" in
        "$prefix"*) ;;
        *) fail "entry $id: key $key is not under $prefix" ;;
    esac

    # Drop the grouping folder directly under the category, keep the rest.
    under="${relative#"$prefix"}"
    rest="${under#*/}"
    if [ "$rest" = "$under" ] || [ -z "$rest" ]; then
        fail "entry $id: key $key needs a grouping folder under $prefix"
    fi
    case "/$rest/" in
        */../*) fail "entry $id: key $key walks upward" ;;
    esac

    source="$mount/$relative"
    target="$dest/$rest"
    case "$written" in
        *"|$target|"*) fail "entry $id: $target is already written by another entry" ;;
    esac
    written="$written|$target|"

    if [ "$unpacked" = "true" ]; then
        [ -d "$source" ] || fail "entry $id: $source is not on the volume"
        mkdir -p "$target"
        cp -R "$source/." "$target/"
    else
        [ -f "$source" ] || fail "entry $id: $source is not on the volume"
        mkdir -p "$(dirname "$target")"
        cp "$source" "$target"
    fi
    log "$category $id -> ${target#"$app"/}"
done <<SELECTED
$selected
SELECTED

if [ -n "${RISE_SERVER_META:-}" ]; then
    printf '%s\n' "$RISE_SERVER_META" > "$app/servermeta.json"
else
    log "warning: RISE_SERVER_META is not set; no servermeta.json written"
fi

cd "$app"
log "starting Hytale on port $game_port for $types, auth mode $auth_mode"
# The agent reports to the Sentry project in RISE_SERVER_META and does nothing without one, so it
# is always loaded. --disable-sentry is always passed: the server would otherwise report to
# Hypixel's Sentry, and its own Sentry.init would replace the agent's.
# Flags are whitespace-separated by design.
# shellcheck disable=SC2086
exec java -javaagent:"$sentry_agent" $jvm_flags -jar "$jar" --bind "0.0.0.0:$game_port" --assets "$assets" \
    --auth-mode "$auth_mode" --disable-sentry $server_flags "$@"
