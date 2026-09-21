#!/usr/bin/env bash
# Sync the chipyard source tree to the compile host, deleting stale files.
#
# usage: sync.sh [options] [HOST]      HOST defaults to $SYNC_HOST, then caramel
#   -n, --dry-run      show what would be deleted/transferred, change nothing
#   --no-manifest      reuse manifest.txt instead of regenerating it
#   --no-verify        skip the sha256 comparison after syncing
#   --prev FILE        previous manifest to diff against, overriding the one
#                      stored on the host (to bootstrap deletion tracking)
#   --force            allow a large deletion (see MAX_DELETE)
#   -c, --checksum     make rsync compare contents, not size+mtime (use when
#                      verify fails on files rsync thought were up to date)
#
#   REMOTE_CY=<path>   tree location on the host (default: same path as here;
#                      it must be the same for the build to be exact)
#   HOST "."           sync into REMOTE_CY on this machine (for testing)
#
# Steps: regenerate manifest.txt; delete on the host every file that was in the
# last synced manifest but isn't in this one; rsync the manifest's files;
# compare sha256 of every manifest file on both sides; record the manifest on
# the host as the new "last synced" state. Files outside the manifest (build
# outputs, SBT state) are never touched, so incremental builds survive.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CY_DIR=$(cd "$SCRIPT_DIR/../../.." && pwd)
MANIFEST=$SCRIPT_DIR/manifest.txt
REMOTE_CY=${REMOTE_CY:-$CY_DIR}
STATE=.cache/firesim-remote-build/manifest.last   # relative to host's $HOME
MAX_DELETE=${MAX_DELETE:-500}

dry_run=
regen=1
verify=1
prev_override=
force=
checksum=
host=${SYNC_HOST:-caramel}
while [ $# -gt 0 ]; do
    case $1 in
        -n|--dry-run)  dry_run=1 ;;
        --no-manifest) regen= ;;
        --no-verify)   verify= ;;
        --prev)        prev_override=$(realpath "$2"); shift ;;
        --force)       force=1 ;;
        -c|--checksum) checksum=1 ;;
        -*)            sed -n '2,22p' "$0"; exit 1 ;;
        *)             host=$1 ;;
    esac
    shift
done

export LC_ALL=C
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# One SSH connection for the whole run, so a password is asked for at most once.
# (Socket path kept short: unix sockets are limited to ~104 chars.)
ssh_opts=(-o ControlMaster=auto -o "ControlPath=/tmp/fsync-ssh-%C" -o ControlPersist=60)
if [ -n "${REMOTE_MAKE_BATCH:-}" ]; then
    ssh_opts+=(-o BatchMode=yes -o ConnectTimeout=15)
fi
rsh() {  # run a shell snippet on the host
    if [ "$host" = . ]; then bash -c "$1"; else ssh "${ssh_opts[@]}" "$host" "$1"; fi
}
dest() {
    if [ "$host" = . ]; then echo "$REMOTE_CY/"; else echo "$host:$REMOTE_CY/"; fi
}
hash_files() {  # read NUL-separated relative paths on stdin, print "hash  path"
    echo "cd $(printf '%q' "$1") && xargs -0 -r sha256sum 2>/dev/null | sort -k2"
}

# 1. manifest
if [ -n "$regen" ]; then
    "$SCRIPT_DIR/make-manifest.sh" "$MANIFEST" 2>/dev/null
fi
[ -s "$MANIFEST" ] || { echo "error: $MANIFEST is empty or missing" >&2; exit 1; }
sort -u "$MANIFEST" > "$tmp/new"
echo "manifest: $(wc -l < "$tmp/new") files"

echo "HERE 1"
# 2. stale files = last synced manifest minus this one
rsh "mkdir -p $(printf '%q' "$REMOTE_CY")"
if [ -n "$prev_override" ]; then
    sort -u "$prev_override" > "$tmp/prev"
else
    rsh "cat \"\$HOME/$STATE\" 2>/dev/null || true" | sort -u > "$tmp/prev"
fi
if [ ! -s "$tmp/prev" ]; then
    echo "warning: no previous manifest on $host; stale files can't be found this time" >&2
fi
comm -23 "$tmp/prev" "$tmp/new" > "$tmp/stale"

# only plain relative paths; a malformed manifest must not delete outside the tree
if grep -qE '^/|(^|/)\.\.(/|$)' "$tmp/stale"; then
    echo "error: stale list has absolute or '..' paths; refusing to delete" >&2
    exit 1
fi
n_stale=$(wc -l < "$tmp/stale")
if [ "$n_stale" -gt "$MAX_DELETE" ] && [ -z "$force" ] && [ -z "$dry_run" ]; then
    echo "error: $n_stale files would be deleted (limit $MAX_DELETE); check with -n, then use --force" >&2
    exit 1
fi

echo "HERE 2"
# 3. delete stale files (and directories left empty by that)
echo "stale: $n_stale files"
if [ "$n_stale" -gt 0 ]; then
    if [ -n "$dry_run" ]; then
        sed 's/^/  delete /' "$tmp/stale" | head -50
        [ "$n_stale" -gt 50 ] && echo "  ... ($n_stale total)"
    else
        tr '\n' '\0' < "$tmp/stale" | rsh "cd $(printf '%q' "$REMOTE_CY") && xargs -0 -r rm -f --"
        sed 's|/[^/]*$||;t;d' "$tmp/stale" | sort -u | tr '\n' '\0' |
            rsh "cd $(printf '%q' "$REMOTE_CY") && xargs -0 -r rmdir -p --ignore-fail-on-non-empty -- 2>/dev/null || true"
    fi
fi

# 4. transfer
rsync_opts=(-a --files-from="$tmp/new" --stats)
[ -n "$dry_run" ] && rsync_opts+=(-n)
[ -n "$checksum" ] && rsync_opts+=(-c)
[ "$host" != . ] && rsync_opts+=(-e "ssh ${ssh_opts[*]}")
rsync "${rsync_opts[@]}" "$CY_DIR/" "$(dest)" | grep -E '^(Number of regular files transferred|Total transferred file size)'

if [ -n "$dry_run" ]; then
    echo "dry run: nothing changed"
    exit 0
fi

# 5. verify. Dangling symlinks hash to nothing on both sides, so they drop out
#    of both lists; a file missing on one side shows up in the diff.
if [ -n "$verify" ]; then
    tr '\n' '\0' < "$tmp/new" | bash -c "$(hash_files "$CY_DIR")" > "$tmp/h-local"
    tr '\n' '\0' < "$tmp/new" | rsh "$(hash_files "$REMOTE_CY")" > "$tmp/h-remote"
    if ! diff "$tmp/h-local" "$tmp/h-remote" > "$tmp/h-diff"; then
        echo "FAIL: $(grep -c '^[<>]' "$tmp/h-diff") hash lines differ after sync:" >&2
        head -20 "$tmp/h-diff" >&2
        echo "(rerun with --checksum if rsync skipped changed files)" >&2
        exit 1
    fi
    echo "verify: $(wc -l < "$tmp/h-local") files match"
fi

# 6. record what the host now has
rsh "mkdir -p \"\$HOME/$(dirname "$STATE")\" && cat > \"\$HOME/$STATE\"" < "$tmp/new"
echo "synced to $host:$REMOTE_CY"
