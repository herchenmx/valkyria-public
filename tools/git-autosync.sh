#!/bin/sh
# Keeps a local clone converged on origin without ever touching work in
# progress. Intended to run from launchd every couple of minutes (see
# com.hevy.gitsync.plist), so the Mac stays current whether or not a Claude
# session -- which only syncs at SessionStart/SessionEnd -- is running.
#
# Usage: git-autosync.sh /absolute/path/to/repo
#
# Safety rules, in order of application:
#   - never merges into a dirty worktree (fetch only, so nothing is lost)
#   - never merges anything but a fast-forward (no auto-resolved conflicts)
#   - never touches a detached HEAD or a branch with no upstream
#   - exits 0 on every failure path, so launchd does not throttle the job
#
# Logging is deliberately quiet: at a 2-minute interval, logging every no-op
# would add ~700 lines a day. It writes only on a state change, deduped via
# .git/autosync.state.

set -u

# launchd hands the job a minimal PATH that does not include git.
PATH=/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin
export PATH

REPO="${1:-}"
[ -n "$REPO" ] || { echo "usage: $0 /path/to/repo" >&2; exit 2; }
cd "$REPO" 2>/dev/null || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

LOG="$REPO/.claude/sync.log"
STATE="$(git rev-parse --git-dir)/autosync.state"
mkdir -p "$REPO/.claude" 2>/dev/null

TN=$(command -v terminal-notifier 2>/dev/null || true)
[ -n "$TN" ] || TN=/opt/homebrew/bin/terminal-notifier

log() {
    echo "$(date '+%F %T') [autosync] $1" >> "$LOG"
}

# Only log when the situation differs from the last thing logged, so a repo
# that sits dirty-and-behind for an hour produces one line, not thirty.
log_once() {
    [ -f "$STATE" ] && [ "$(cat "$STATE" 2>/dev/null)" = "$1" ] && return 0
    printf '%s' "$1" > "$STATE"
    log "$2"
}

# A fetch failure is expected on a sleeping or offline laptop, so it must not
# spam the log -- but it must not be invisible either. A background agent has
# no terminal to prompt at, so anything needing credentials (a private repo
# over HTTPS whose keychain entry is not reachable) fails here permanently,
# and a silent exit makes that indistinguishable from "nothing to do".
# log_once keys on the failure alone, so a laptop offline all night still
# produces exactly one line.
if ! FETCH_ERR=$(git fetch origin --prune 2>&1); then
    log_once "fetchfail" "git fetch failed: $(printf '%s' "$FETCH_ERR" | tr '\n' ' ' | cut -c1-300)"
    exit 0
fi

BRANCH=$(git symbolic-ref --quiet --short HEAD 2>/dev/null) || {
    log_once "detached" "detached HEAD -- fetched only"; exit 0; }

UPSTREAM=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null) || {
    log_once "noupstream:$BRANCH" "$BRANCH has no upstream -- fetched only"; exit 0; }

BEHIND=$(git rev-list --count "HEAD..$UPSTREAM" 2>/dev/null || echo 0)
[ "$BEHIND" -gt 0 ] 2>/dev/null || { printf 'current' > "$STATE"; exit 0; }

if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    log_once "dirty:$BRANCH:$BEHIND" \
        "$BRANCH is $BEHIND behind $UPSTREAM but the tree is dirty -- not pulling"
    exit 0
fi

OLD=$(git rev-parse --short HEAD)
if git merge --ff-only --quiet "$UPSTREAM" 2>/dev/null; then
    NEW=$(git rev-parse --short HEAD)
    printf 'current' > "$STATE"
    log "fast-forwarded $BRANCH $OLD..$NEW ($BEHIND commit(s) from $UPSTREAM)"
    if [ -x "$TN" ]; then
        "$TN" -title "Claude sync" \
              -message "Pulled $BEHIND commit(s) into $BRANCH" \
              -sound Ping -open "file://$LOG"
    fi
else
    # Diverged: local commits the remote does not have. Never auto-resolve --
    # this is the one case that genuinely needs a human.
    log_once "diverged:$BRANCH:$BEHIND" \
        "$BRANCH has diverged from $UPSTREAM ($BEHIND behind) -- manual merge needed"
fi

exit 0
