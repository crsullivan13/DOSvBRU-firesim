# Remote compile

Runs FireSim metasim/driver builds (Chisel, Golden Gate, Verilator) on a faster
host inside a container that is an exact copy of your local conda env, then
pulls the results back. `buildbitstream` still elaborates locally.

## Setup (once per user)


0. On your local machine and the compile host (caramel or other remote) you need to run.
```
sudo usermod --add-subuids 100000-165535 YOUR_USERNAME
sudo usermod --add-subgids 100000-165535 YOUR_USERNAME
(install podman via apt before the below)
podman system migrate
podman unshare cat /proc/self/uid_map
podman unshare cat /proc/self/gid_map
```
1. **Local:** Podman installed. Your firesim (sourceme stuff) `.conda-env` works and conda is at
   `~/miniforge3` (else set `CONDA_BASE`). `Containerfile`'s `FROM` matches your
   Ubuntu release.
2. **Compile host:** Podman installed, `/etc/subuid` + `/etc/subgid` entries for
   you, and your chipyard tree at the **same absolute path** as locally. Paths need to match.
3. **SSH:** key login to the host from the shell you run `firesim` in, via the
   manager's agent (`ssh-add` your key there). Check:
   ```
   ssh localhost "SSH_AUTH_SOCK=$SSH_AUTH_SOCK ssh -o BatchMode=yes caramel true"
   ```
4. **Image:** build and check it (the image is yours; it bakes in your paths).
   ```
   ./build-image.sh
   ./verify-image.sh --smoke (this makes sure you can build GCD example)
   ```

## Use

```
export FIRESIM_COMPILE_HOST=caramel # if you don't export, it runs local as usual
firesim infrasetup        # metasim builds now run on caramel
```

infrasetup will still spit out as [localhost] but it is running on the remote. You can verify
by looking top/htop on the remote.

The first build may take a while as caches get set up on the remote.

I haven't tested this path with buildbitstream so that path still uses local compile.

By hand, with the same args you would give `make` in `sims/firesim/sim`:
```
./remote-make.sh -- TARGET_PROJECT=midasexamples DESIGN=GCD verilator
```

## Scripts

| Script | Does |
|---|---|
| `build-image.sh` | Builds the image from your `.conda-env`; writes `IMAGE_TAG`. |
| `verify-image.sh` | Checks the image matches your env; `--smoke` builds GCD in it. |
| `make-manifest.sh` | Lists the source files to sync (`manifest.txt`). |
| `sync.sh` | Syncs the manifest to the host, deletes stale files, verifies hashes. |
| `remote-make.sh` | Sync, ship image if missing, build in container, pull outputs back. |
| `container-make.sh` | Runs inside the container; called by `remote-make.sh`. |
| `fingerprint.sh` | Prints an env/source fingerprint for comparing machines. |

## Notes

- First build on the host is slow (cold SBT). Seeding `~/.cache/coursier` and
  `~/.sbt` on the host avoids re-downloading dependencies.
- Rebuild the image whenever your conda env changes.
- Per user on the host: ~8 GB image, ~8 GB env volume (`fsenv-<tag>`), ~2.3 GB
  source, plus build outputs.
- Golden Gate output is not deterministic run to run (SRAM model port order),
  so compare remote output against a fresh local run, not an old one.
