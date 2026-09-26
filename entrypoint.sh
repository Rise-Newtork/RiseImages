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

log() { echo "entrypoint: $*"; }
fail() { echo "entrypoint: $*" >&2; exit 1; }

[ -n "$types" ] || fail "RISE_SERVER_TYPES is not set"
[ -d "$mount" ] || fail "$mount is not mounted"
[ -f "$index" ] || fail "$index is missing; DependSync has not synced this node"
[ -f "$jar" ] || fail "$jar is missing; DependSync has not installed Hytale"
[ -f "$assets" ] || fail "$assets is missing; DependSync has not installed Hytale"
[ -w "$app" ] || fail "$app is not writable"

# Accepts STICK_GAME as well as SERVER_TYPE_STICK_GAME; compared case-insensitively
# because the protocol spells one type SERVER_TYPE_DUEL_2v2.
wanted=$(printf '%s' "$types" | tr ',' '\n' | sed 's/^ *//;s/ *$//;/^$/d' \
    | tr '[:lower:]' '[:upper:]' | sed 's/^\(SERVER_TYPE_\)\{0,1\}/SERVER_TYPE_/' | jq -R . | jq -sc .)

selected=$(jq -r --argjson wanted "$wanted" '
    .entries[]
    | select(any(.serverTypes[]; . == "SERVER_TYPE_UNSPECIFIED" or (ascii_upcase | IN($wanted[]))))
    | [.category, .id, .objectKey, (.unpacked // false)]
    | @tsv' "$index")

[ -n "$selected" ] || log "warning: no entries in $index are tagged for $types"

written=""
while IFS="$(printf '\t')" read -r category id key unpacked; do
    [ -n "$category" ] || continue

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
# Flags are whitespace-separated by design.
# shellcheck disable=SC2086
exec java $jvm_flags -jar "$jar" --bind "0.0.0.0:$game_port" --assets "$assets" --auth-mode "$auth_mode" $server_flags "$@"
