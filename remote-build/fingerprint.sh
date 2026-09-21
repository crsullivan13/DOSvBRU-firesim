#!/usr/bin/env bash
# Print a fingerprint of the build environment or of the chipyard source tree.
# Run it in two places and diff the output; identical output = identical inputs.
#
# usage: fingerprint.sh env [--deep]   toolchain: OS/glibc, tool paths+versions,
#                                      conda package list; --deep also hashes
#                                      every file in .conda-env (slow, ~8 GB)
#        fingerprint.sh src            source: HEAD, submodule commits, and a
#                                      hash of uncommitted + untracked changes
#                                      in every repo

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CY_DIR=$(cd "$SCRIPT_DIR/../../.." && pwd)
CONDA_BASE=${CONDA_BASE:-$HOME/miniforge3}

load_env() {
    # env.sh needs the conda shell function, which non-interactive shells lack
    source "$CONDA_BASE/etc/profile.d/conda.sh"
    set +u
    source "$CY_DIR/env.sh" >/dev/null
    set -u
}

fp_env() {
    local deep=${1:-}
    load_env

    echo "## os"
    (. /etc/os-release && echo "$ID $VERSION_ID")
    ldd --version 2>&1 | sed -n 1p

    echo "## tools"
    # path, binary hash, and version line (where the tool has one)
    local t path ver
    for t in bash make gcc g++ java verilator firtool python3 perl git rsync \
             riscv64-unknown-elf-gcc riscv64-unknown-linux-gnu-gcc spike dtc; do
        if ! path=$(command -v "$t"); then
            echo "$t: MISSING"
            continue
        fi
        case $t in
            java)  ver=$(java -version 2>&1 | sed -n 1p) ;;
            spike) ver= ;;
            *)     ver=$({ "$t" --version 2>&1 || true; } | { grep -i 'version\|[0-9]\.[0-9]' || true; } | sed -n 1p) ;;
        esac
        echo "$t: $path | $(sha256sum < "$(readlink -f "$path")" | cut -c1-16) | $ver"
    done
    echo "RISCV=$RISCV"

    echo "## conda"
    conda list --explicit --md5 -p "$CY_DIR/.conda-env"

    if [ "$deep" = "--deep" ]; then
        echo "## conda-env files"
        (cd "$CY_DIR/.conda-env" &&
            find . \( -type f -o -type l \) -print0 | LC_ALL=C sort -z |
            xargs -0 -P"$(nproc)" -n256 sha256sum | LC_ALL=C sort -k2)
    fi
}

fp_src() {
    local repos r
    echo "## commits"
    git -C "$CY_DIR" rev-parse HEAD
    git -C "$CY_DIR" submodule status --recursive

    echo "## working tree changes"
    repos=$(printf '%s\n' "$CY_DIR"; git -C "$CY_DIR" submodule foreach --recursive --quiet pwd)
    while read -r r; do
        local diff_hash untracked_hash
        diff_hash=$(git -C "$r" diff HEAD --binary | sha256sum | cut -c1-16)
        # entries ending in / are nested (non-submodule) git repos; their
        # contents are not covered here
        untracked_hash=$(cd "$r" && git ls-files --others --exclude-standard -z |
            { grep -zv '/$' || true; } | LC_ALL=C sort -z |
            xargs -0 -r sha256sum | sha256sum | cut -c1-16)
        [ "$r" = "$CY_DIR" ] && r=.
        echo "${r#"$CY_DIR"/} diff=$diff_hash untracked=$untracked_hash"
    done <<< "$repos"
}

case ${1:-} in
    env) fp_env "${2:-}" ;;
    src) fp_src ;;
    *)   sed -n '2,11p' "$0"; exit 1 ;;
esac
