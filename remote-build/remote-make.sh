#!/usr/bin/env bash
# Run a FireSim make target on the compile host inside the build container and
# bring its outputs back here.
#
# usage: remote-make.sh [options] [HOST] -- <make args>
#   e.g. remote-make.sh -- TARGET_PROJECT=midasexamples DESIGN=GCD verilator
#        remote-make.sh -- PLATFORM=xilinx_vcu118 TARGET_PROJECT=firesim \
#            TARGET_PROJECT_MAKEFRAG=... DESIGN=FireSim TARGET_CONFIG=... \
#            PLATFORM_CONFIG=... verilator
#   --no-sync    skip sync.sh (host tree already current)
#   --no-pull    leave outputs on the host
#
#   HOST defaults to $SYNC_HOST, then caramel. HOST "." runs the container on
#   this machine against this tree (sync and pull are skipped); for testing.
#   MAKE_JOBS    parallel make jobs on the host (default: host's nproc)
#
# Steps: sync.sh; ship the image if the host lacks it; run container-make.sh in
# the container; rsync back the config's GENERATED_DIR and OUTPUT_DIR (as
# reported by make) plus the chipyard staging dirs GENERATED_DIR links into.
# The tree must be at the same absolute path on the host as here.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CY_DIR=$(cd "$SCRIPT_DIR/../../.." && pwd)
TAG=$(cat "$SCRIPT_DIR/IMAGE_TAG")

sync=1
pull=1
host=${SYNC_HOST:-caramel}
while [ $# -gt 0 ]; do
    case $1 in
        --no-sync) sync= ;;
        --no-pull) pull= ;;
        --)        shift; break ;;
        -*)        sed -n '2,23p' "$0"; exit 1 ;;
        *)         host=$1 ;;
    esac
    shift
done
[ $# -gt 0 ] || { sed -n '2,23p' "$0"; exit 1; }
make_args=("$@")
if [ "$host" = . ]; then sync= pull=; fi

# Shares sync.sh's SSH connection (same ControlPath).
ssh_opts=(-o ControlMaster=auto -o "ControlPath=/tmp/fsync-ssh-%C" -o ControlPersist=60)
if [ -n "${REMOTE_MAKE_BATCH:-}" ]; then
    ssh_opts+=(-o BatchMode=yes -o ConnectTimeout=15)
fi
rsh() {
    if [ "$host" = . ]; then bash -c "$1"; else ssh "${ssh_opts[@]}" "$host" "$1"; fi
}
q() { printf '%q ' "$@"; }
phase() { printf '\n==> [%s] %s\n' "$(date +%T)" "$*"; }

log=$(mktemp)
trap 'rm -f "$log"' EXIT

# One remote build at a time per host: a second sync would change the tree
# under a running build.
exec 9> "/tmp/remote-make-${host//\//_}.lock"
if ! flock -n 9; then
    echo "waiting for another remote-make on $host..."
    flock 9
fi

t0=$SECONDS

# 1. source
if [ -n "$sync" ]; then
    phase "sync source to $host"
    "$SCRIPT_DIR/sync.sh" "$host"
fi

# 2. image
if [ "$host" != . ] && ! rsh "podman image exists $(q "$TAG")"; then
    phase "shipping $TAG to $host (one-time per image)"
    podman save "$TAG" | ssh "${ssh_opts[@]}" "$host" podman load
fi

# 3. build. The tree mount hides the image's .conda-env, so the per-image
#    volume is mounted over it (filled from the image on first use).
name=remote-make-$(date +%s)-$$
stop_container() {
    echo "interrupted: stopping $name on $host" >&2
    rsh "podman stop -t 5 $name >/dev/null 2>&1 || true"
    exit 130
}
trap stop_container INT TERM HUP

phase "make ${make_args[*]} on $host"
run_cmd="mkdir -p ~/.cache/coursier ~/.sbt ~/.ivy2 && podman run --rm --name $name \
    --userns=keep-id -e HOME=\$HOME -e MAKE_JOBS=${MAKE_JOBS:-} \
    -v $(q "$CY_DIR:$CY_DIR") \
    -v $(q "fsenv-${TAG#*:}:$CY_DIR/.conda-env") \
    -v \$HOME/.cache/coursier:\$HOME/.cache/coursier \
    -v \$HOME/.sbt:\$HOME/.sbt \
    -v \$HOME/.ivy2:\$HOME/.ivy2 \
    --ulimit nofile=16384:16384 \
    -w $(q "$CY_DIR") $(q "$TAG") \
    bash $(q "$SCRIPT_DIR/container-make.sh") $(q "${make_args[@]}")"
set +e
rsh "$run_cmd" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
set -e
trap - INT TERM HUP
if [ "$status" -ne 0 ]; then
    echo "FAIL: remote make exited $status" >&2
    exit "$status"
fi

# 4. outputs
mapfile -t dirs < <(sed -n 's/^@@REMOTE_MAKE_DIR //p' "$log")
[ ${#dirs[@]} -gt 0 ] || { echo "error: make reported no output dirs" >&2; exit 1; }
gen_dir=${dirs[0]}
# GENERATED_DIR holds symlinks into chipyard's staging area (Chisel outputs)
mapfile -t -O ${#dirs[@]} dirs < <(
    rsh "find $(q "$gen_dir") -maxdepth 1 -type l -printf '%l\n' 2>/dev/null" |
        sed 's|/[^/]*$||' | sort -u | grep "^$CY_DIR/"
) || true

if [ -n "$pull" ]; then
    phase "pulling outputs from $host"
    for d in "${dirs[@]}"; do
        if rsh "test -d $(q "$d")"; then
            echo "  ${d#"$CY_DIR"/}"
            mkdir -p "$d"
            rsync -a --delete -e "ssh ${ssh_opts[*]}" "$host:$d/" "$d/"
        fi
    done
else
    echo "outputs left on $host:"
    printf '  %s\n' "${dirs[@]#"$CY_DIR"/}"
fi

phase "done in $((SECONDS - t0))s"
