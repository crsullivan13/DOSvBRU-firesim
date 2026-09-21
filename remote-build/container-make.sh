#!/usr/bin/env bash
# Runs inside the compile container (started by remote-make.sh): run make in
# sims/firesim/sim with the given args, then print the output directories of
# that config as "@@REMOTE_MAKE_DIR <path>" lines so the caller knows what to
# pull back.
#
# usage: container-make.sh <make args>    e.g. TARGET_PROJECT=midasexamples DESIGN=GCD verilator
#   MAKE_JOBS  parallel jobs (default: nproc)

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."   # sims/firesim
source "$CONDA_BASE/etc/profile.d/conda.sh"
set +u
source sourceme-manager.sh --skip-ssh-setup >/dev/null
set -u
cd sim

# VAR=value args pick the config; the rest are targets
vars=()
for a in "$@"; do
    if [[ $a == *=* ]]; then vars+=("$a"); fi
done

make -j"${MAKE_JOBS:-$(nproc)}" "$@"

make --no-print-directory "${vars[@]}" \
    --eval='print-%: ; @echo "@@REMOTE_MAKE_DIR $($*)"' \
    print-GENERATED_DIR print-OUTPUT_DIR
