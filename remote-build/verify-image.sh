#!/usr/bin/env bash
# Check that the compile image matches this machine's environment.
#
# usage: verify-image.sh [--deep] [--smoke] [TAG]
#   TAG      image to check (default: contents of IMAGE_TAG)
#   --deep   also compare a sha256 of every file in .conda-env
#   --smoke  also build the midasexamples GCD verilator metasim inside the
#            container, to catch anything the build pulls from the host OS
#
#   ENGINE=podman|docker (default: podman)

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CY_DIR=$(cd "$SCRIPT_DIR/../../.." && pwd)
ENGINE=${ENGINE:-podman}

deep=
smoke=
tag=
for arg in "$@"; do
    case $arg in
        --deep)  deep=--deep ;;
        --smoke) smoke=1 ;;
        -*)      sed -n '2,10p' "$0"; exit 1 ;;
        *)       tag=$arg ;;
    esac
done
tag=${tag:-$(cat "$SCRIPT_DIR/IMAGE_TAG")}

# Same invocation the manager will use: host UID, tree at its host path.
# The tree mount would hide the image's $CY_DIR/.conda-env (behind the host's
# own env here, or nothing on the compile host), so a per-image named volume is
# mounted over it; podman fills it from the image on first use.
run_in_container() {
    local userns=()
    [ "$ENGINE" = podman ] && userns=(--userns=keep-id)
    [ "$ENGINE" = docker ] && userns=(--user "$(id -u):$(id -g)")
    "$ENGINE" run --rm "${userns[@]}" \
        -e HOME="$HOME" \
        -v "$CY_DIR:$CY_DIR" \
        -v "fsenv-${tag#*:}:$CY_DIR/.conda-env" \
        -w "$CY_DIR" \
        "$tag" bash -c "$1"
}

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT

echo "fingerprinting host..."
"$SCRIPT_DIR/fingerprint.sh" env $deep > "$out/host"
echo "fingerprinting $tag..."
run_in_container "$SCRIPT_DIR/fingerprint.sh env $deep" > "$out/container"

if diff -u --label host "$out/host" --label "$tag" "$out/container"; then
    echo "PASS: environment matches"
else
    echo "FAIL: environment differs (see diff above)" >&2
    exit 1
fi

if [ -n "$smoke" ]; then
    echo "smoke build: midasexamples GCD verilator metasim..."
    run_in_container "
        set -e
        source \$CONDA_BASE/etc/profile.d/conda.sh
        cd sims/firesim
        source sourceme-manager.sh --skip-ssh-setup
        cd sim
        make TARGET_PROJECT=midasexamples DESIGN=GCD verilator
    "
    echo "PASS: smoke build succeeded"
fi
