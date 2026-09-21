#!/usr/bin/env bash
# Build the FireSim compile image from this machine's conda env.
#
# usage: build-image.sh
#   ENGINE=podman|docker (default: podman)
#   CONDA_BASE=<miniforge dir> (default: $HOME/miniforge3)
#
# Writes the resulting tag to IMAGE_TAG next to this script. The tag is derived
# from the env contents, so an unchanged env reproduces the same tag.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CY_DIR=$(cd "$SCRIPT_DIR/../../.." && pwd)
CONDA_ENV=$CY_DIR/.conda-env
CONDA_BASE=${CONDA_BASE:-$HOME/miniforge3}
ENGINE=${ENGINE:-podman}
STAGE=$SCRIPT_DIR/.stage
LIBC_VERSION=$(dpkg-query -W -f='${Version}' libc6)

[ -d "$CONDA_ENV" ] || { echo "error: no conda env at $CONDA_ENV" >&2; exit 1; }
[ -x "$CONDA_BASE/bin/conda" ] || { echo "error: no conda at $CONDA_BASE" >&2; exit 1; }
command -v "$ENGINE" >/dev/null || { echo "error: $ENGINE not installed" >&2; exit 1; }

# Tag = hash of everything that goes into the image.
tag_hash=$(
    {
        cat "$SCRIPT_DIR/Containerfile"
        echo "$LIBC_VERSION"
        "$CONDA_BASE/bin/conda" --version
        "$CONDA_BASE/bin/conda" list --explicit --md5 -p "$CONDA_ENV"
        # riscv-tools, firtool etc. are built by build-setup.sh, not tracked by conda
        (cd "$CONDA_ENV/riscv-tools" && find . -printf '%P %s %T@\n' | LC_ALL=C sort)
    } | sha256sum | cut -c1-12
)
TAG=firesim-build:$tag_hash

# Stage the build context with hardlinks (no extra disk when on the same
# filesystem; rsync falls back to copying otherwise).
rm -rf "$STAGE"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE"
cp "$SCRIPT_DIR/Containerfile" "$STAGE/"
echo "staging conda env..."
rsync -a --link-dest="$CONDA_ENV/" "$CONDA_ENV/" "$STAGE/conda-env/"
rsync -a --link-dest="$CONDA_BASE/" --exclude=/pkgs --exclude=/envs \
    "$CONDA_BASE/" "$STAGE/miniforge3/"

echo "building $TAG with $ENGINE..."
"$ENGINE" build \
    --build-arg HOME_DIR="$HOME" \
    --build-arg CY_DIR="$CY_DIR" \
    --build-arg LIBC_VERSION="$LIBC_VERSION" \
    -t "$TAG" \
    -f "$STAGE/Containerfile" \
    "$STAGE"

echo "$TAG" > "$SCRIPT_DIR/IMAGE_TAG"
echo "built $TAG (written to $SCRIPT_DIR/IMAGE_TAG)"
