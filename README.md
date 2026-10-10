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

Anything unexpected aborts before the server starts: a key outside its category's prefix, a key
with no grouping folder, a path that walks upward, a file missing from the volume, or two
entries claiming the same target. The Hytale jar and `Assets.zip` come from `hytale/` on the
same volume; DependSync installs those with the official downloader, not from the bucket.

## Building and testing

```bash
docker build -t rise-hytale .
sh test/run.sh
```

`test/run.sh` runs the real entrypoint against `test/fixtures` with a fake `java` on the PATH
that records its argv, so it checks the staging rules, the flag ordering, the default auth mode
and each refusal without a JVM or a Docker daemon. It needs `jq`.

`.github/workflows/publish.yml` runs `shellcheck` over `entrypoint.sh`, `test/run.sh` and
`test/bin/java` and then the harness, on every push and pull request. The image is built on
every run and pushed to `ghcr.io/<owner>/rise-hytale` only for `v*` tags, tagged with the
version and `latest`.
