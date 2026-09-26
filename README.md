# rise-hytale

The one image every Rise game container runs. It holds a JRE, jq and the entrypoint; the
Hytale server, mods, worlds and configs come from the DependSync volume at runtime, chosen by
`RISE_SERVER_TYPES`. The environment the container creator passes is listed in
RiseContainerCreator's README.

The entrypoint refuses to start, with the reason, when the volume is not mounted, DependSync has
not synced the node yet, or two entries would land on the same path.

## Releasing

Push a `v*` tag. The workflow runs the fixture suite, then publishes
`ghcr.io/rise-newtork/rise-hytale:<version>` and `:latest`. Nodes set `RISE_IMAGE` to a
version tag, so a later release never changes a running deployment.

## Testing

```bash
sh test/run.sh
```

Runs the entrypoint against `test/fixtures` with a fake `java` that records how it was called.
Needs jq.
