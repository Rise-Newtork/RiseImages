# RiseImages

Container images for the Rise network. There is one so far: `rise-hytale`, the Hytale game
server image. A container of it stages the mods, worlds and configs its server types need out
of the DependSync volume, writes `servermeta.json`, and execs the Hytale server jar in the
foreground, so the container's lifetime is the server's lifetime.

Note the name clash: the working folder is `rise-hytale` but the repo is
`git@github.com:Rise-Newtork/RiseImages.git`. `rise-hytale` names the image, not the repo.

## How it fits

`../RiseContainerCreator` runs one `docker run` of this image per server, with the DependSync
volume mounted read-only and the environment below. `../DependSync` fills that volume from the
bucket `../RiseConfigured` mirrors and writes the `index.json` this image reads. The mods that
end up inside - `../RiseHubs`, `../RiseMinigames` - read `servermeta.json` from the working
directory to learn their `containerName`, `controlPlaneEndpoint` and `managementPort`, and call
`../RiseMatchmaker` with them. Players never reach a container directly; `../RiseProxy` does.

## Player authentication is off

`RISE_AUTH_MODE` defaults to `insecure` (since `8def723`, 2026-09-26), so the server accepts
connections with no account session. That is the deployment baseline for the whole network, not
a dev shortcut: the proxy terminates the player's authenticated session at its edge and opens
the backend connection with insecure handshake options, so a backend that demanded
authentication could not be joined at all.

It also means **a container's game port must never be reachable from the internet**. Anyone who
can reach it joins as whatever name they like. The creator publishes on `RISE_BIND_HOST` for
this reason; keep that on the node's WireGuard address or firewall the published range.

## servermeta.json

The mods' READMEs say "the image writes `servermeta.json`", which is true but only half the
story: the entrypoint writes the file, and `RiseContainerCreator` writes its contents. The
creator serialises `life.ContainerMeta` (`../RiseProtocol`) to one line of JSON into
`RISE_SERVER_META`, and the entrypoint copies that string verbatim to `$RISE_APP_DIR/servermeta.json`
next to the server. It does not parse it or fill anything in. With the variable unset the
entrypoint logs a warning and writes nothing, which is how the mods detect a dev server.

`RISE_MANAGEMENT_PORT` is deliberately not read here: the server jar does not bind it, the mod
inside does, and it learns the number from `servermeta.json`.

## Sentry

The server reports to Sentry through `agent/`, a small java agent the image builds and loads with
`-javaagent` on every start. It reads `sentry` out of `RISE_SERVER_META`
(`{"dsn": ..., "environment": ..., "release": ..., "logsLevel": ...}`); with that block absent or
its `dsn` blank it does nothing, so a dev server reports nowhere. With a DSN:

- every line the server and its mods log at `logsLevel` and up goes to Sentry Logs. `logsLevel` is
  a java.util.logging name (`SEVERE`, `WARNING`, `INFO`, `FINE`, ...); blank is `INFO`, and an
  unknown name is `INFO` with a warning on stderr;
- until the server logs `Hytale Server Booted!` (the line the creator waits for), only SEVERE
  lines go to Logs. Every boot prints the same few hundred lines; a boot that fails still carries
  the last of them as breadcrumbs on its issue;
- a few lines every server prints identically are never sent to Logs; they are listed, with the
  reason for each, in `agent/src/main/java/com/risehytale/sentryagent/Noise.java`;
- every SEVERE line becomes an issue, with its exception's stack trace when it has one, mods
  included. An uncaught exception is `fatal`, and so is one that stops the server starting. A
  WARNING with an exception stays a log line, as the server's own Sentry handler has it;
- a stack trace printed to `System.out` or `System.err` (`printStackTrace()`) becomes one issue
  and one log entry, not a line each. Issues are grouped by exception type and the first frame in
  `com.risehytale` or `com.hypixel.hytale` (else the first outside the JDK), so a repeat lands on
  the same issue whatever its message;
- `server_name` is the container name, and events carry the tags `container`, `node`,
  `server_types` and, on a hub, `hub_type` (log entries carry the same as attributes, plus
  `logger`); one logged on a world thread whose world is named by a minted match ID
  (`[0-9A-HJKMNP-TV-Z]{4,13}`, as RiseMinigames names a match's world) also carries `match_id`, so
  a non-match world must never get an all-uppercase name like `SPAWN`. Printed stack traces are
  not tagged;
- the password in a `user:password@host` is masked.

It uses the Sentry SDK the server jar bundles and ships none of its own. It has to load before the
server's logging starts, which is why it is an agent rather than a mod: a mod loads well after
start-up, and the server's own Sentry handler only sees records with an exception and none from a
third-party mod. Instead it subscribes to the root of `HytaleLogger`, through which every line
passes, including `System.out` and `System.err`. Those two arrive one record per printed line,
so the agent holds each stream's lines until one does not continue a stack trace (`\tat`,
`Caused by:`, `... N more`, `Suppressed:`) or the stream has been quiet for 200ms. A printed
exception that was also captured in the last 10 seconds, as the server does with a failed start,
is not made an issue twice.

The entrypoint always passes `--disable-sentry`, whatever `RISE_SERVER_FLAGS` says. Without it the
server reports to Hypixel's Sentry, and its `Sentry.init` would replace the agent's.

## Environment

The entrypoint reads these; the `Dockerfile` defaults are in brackets.

| Variable | Meaning |
| --- | --- |
| `RISE_SERVER_TYPES` | Required. Comma-separated `SERVER_TYPE_*` names, which select what to stage. |
| `RISE_DEPEND_MOUNT` | Where the DependSync volume is mounted [`/dependfiles`]. |
| `RISE_APP_DIR` | Where the server is staged and run [`/app`]. |
| `RISE_GAME_PORT` | What the server binds inside the container [`5520`]. |
| `RISE_SERVER_META` | `life.ContainerMeta` as one line of JSON. |
| `RISE_AUTH_MODE` | `--auth-mode` [`insecure`]. |
| `RISE_JVM_FLAGS` | Whitespace-separated, placed before `-jar`. |
| `RISE_SERVER_FLAGS` | Whitespace-separated, placed after the fixed flags [`--disable-file-watcher`]. |

Type names are matched case-insensitively and the `SERVER_TYPE_` prefix is optional, so
`stick_game` and `SERVER_TYPE_STICK_GAME` both work.

## Staging

`$RISE_DEPEND_MOUNT/index.json` is DependSync's record of what the node has installed. The
entrypoint selects the entries whose `serverTypes` include one of the wanted types or
`SERVER_TYPE_UNSPECIFIED`, then copies each by category: `mods/` to `mods/`, `worldFiles/` to
`universe/worlds/`, `configs/` to `configs/`, `root/` to the app root. The first folder under
the category is a grouping folder and is dropped, so `configs/hub/hubs/shop.json` lands at
`configs/hubs/shop.json`. An entry with `unpacked` is a directory named after its id.

Two kinds of entry are deliberately staged nowhere: one that names a `skeleton` (a map's chunks)
and one another entry names as its skeleton. Those are the halves of an instance template, which
`../RiseContainerCreator` composes into an asset pack and binds in read-only at
`$RISE_APP_DIR/mods/<pack>`; an instance resolves only against a registered pack, and nothing
reads `universe/worlds` for a match. Every other `worldFiles` entry still stages there.

`index.json`'s own `schemaVersion` must be 1 or 2. 2 is where the `skeleton` relation lives; 1 is
from a DependSync before it, accepted with a warning, and on one of those nothing is a pack
component so maps stage as they used to. Any other version is refused before the server starts.

Anything unexpected aborts before the server starts: a key outside its category's prefix, a key
with no grouping folder, a path that walks upward, a file missing from the volume, or two
entries claiming the same target. The Hytale jar and `Assets.zip` come from `hytale/` on the
same volume; DependSync installs those with the official downloader, not from the bucket.

## Building and testing

```bash
docker build -t rise-hytale .
sh test/run.sh
(cd agent && ./gradlew test)
```

`test/run.sh` runs the real entrypoint against `test/fixtures` with a fake `java` on the PATH
that records its argv, so it checks the staging rules, the flag ordering, the default auth mode
and each refusal without a JVM or a Docker daemon. It needs `jq`.

The agent is built in the image's first stage and compiled against `io.sentry:sentry:8.30.0` and
`org.mongodb:bson` without bundling either, since the server jar it runs beside carries both. Its
tests cover reading the meta, rebuilding printed stack traces and which lines reach Logs; the
logging hook itself needs the real server jar, which only exists on the DependSync volume.

`.github/workflows/publish.yml` runs `shellcheck` over `entrypoint.sh`, `test/run.sh` and
`test/bin/java`, then the harness and the agent's tests, on every push and pull request. The image is built on
every run and pushed to `ghcr.io/<owner>/rise-hytale` only for `v*` tags, tagged with the
version and `latest`.
