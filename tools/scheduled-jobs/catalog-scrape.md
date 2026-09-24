# Catalog scrape (scheduled, unattended)

Paste this into the scheduled task. Only the two `MAIN` / `WORKTREES` lines
need touching if the repo ever moves again.

---

This task runs UNATTENDED on a schedule. The user is not present — execute autonomously. Its job is to run the local Hevy catalog scraper and, only if that surfaces code changes, deliver them as a pull request and auto-merge it (unless it conflicts, in which case leave it open and raise a loud alert). It must NEVER leave uncommitted changes to tracked files in the main working directory.

Every path in this task derives from these two variables. Set them once; change nothing else if the repo moves:

```
MAIN=/Users/maria/Projects/hevy-wear-os-sdk28
WORKTREES=/Users/maria/Projects/hevy-worktrees
```

Do not place either path under ~/Downloads, ~/Documents, or ~/Desktop. macOS privacy protection blocks scheduled background jobs from those folders, and the failure looks like a permissions bug on files that are plainly readable when you run them by hand.

## 1. Run the scraper (in MAIN — this is safe)
The scraper lives at $MAIN/scripts/hevy_scrape.py. Both /scripts and its /data output are gitignored, so running it only writes gitignored files and does NOT dirty the tracked tree.

```
cd "$MAIN"
python3 scripts/hevy_scrape.py
```

Then VERIFY the tracked tree is still clean:

```
git -C "$MAIN" status --porcelain -uno
```

This should print nothing (only gitignored /data changed). If it prints any tracked files, the scrape unexpectedly modified tracked code — revert those in MAIN (`git -C "$MAIN" checkout -- <files>`) and reproduce the equivalent change in the worktree in step 2 instead. Never leave MAIN's tracked tree dirty.

## 2. If the findings warrant code changes (e.g. a new Hevy app version to detect, catalog/exercise mapping updates), isolate them in a worktree

```
mkdir -p "$WORKTREES"
git -C "$MAIN" fetch origin
TS=$(date +%Y%m%d-%H%M%S)
BR="auto/catalog-scrape-$TS"
WT="$WORKTREES/catalog-scrape-$TS"
git -C "$MAIN" worktree add -b "$BR" "$WT" origin/main
cd "$WT"
```

Note: /scripts is gitignored, so the scraper does NOT exist inside the worktree. That is fine — the scrape already ran in MAIN at step 1; the worktree is only for the resulting code changes.

### Branch discipline (important)
You are on branch $BR inside the worktree. Every commit goes to $BR and every push goes to origin/$BR — NEVER to main. Your FIRST push must set the upstream: `git push -u origin "$BR"`; subsequent pushes are plain `git push`. Never `git push origin main`, never `git checkout main`, never merge into main from inside the worktree.

Make the code changes inside $WT following the implementation workflow to its full extent EXCEPT device installs: create/update unit tests, run them, build the affected release APK(s), update the PRDs. Do NOT install to any device and do NOT run SET_COMMIT_HASH.

When you run tests or builds, use the aggregate task names:

```
./gradlew :core:test :app:test :companion:test :app:assembleRelease :companion:assembleRelease
```

Use `:app:test` / `:companion:test`, NOT `:app:testReleaseUnitTest`. AGP 9.2.1 does not define a `testReleaseUnitTest` task; asking for it fails at task selection before a single test runs. This is the same command `.github/workflows/build.yml` runs on every push, so a green run here predicts a green CI run after merge.

## 3. Publish

```
cd "$WT"
git add -A
git commit -m "<clear message describing the catalog/version change>"
git push -u origin "$BR"
gh pr create --base main --head "$BR" --title "Catalog scrape — $TS" --body "<what the scrape found and what you changed>"
```

## 4. Auto-merge with conflict guard
GitHub computes mergeability asynchronously, so confirm it before acting. Run this exact block (the `banner` helper posts a click-to-open-the-log notification via terminal-notifier, falling back to osascript):

```
LOG="$MAIN/.claude/sync.log"
TN=/opt/homebrew/bin/terminal-notifier
banner(){ if [ -x "$TN" ]; then "$TN" -title "$2" -message "$1" -sound "$3" -open "file://$LOG"; else osascript -e 'on run argv' -e 'display notification (item 1 of argv) with title "Claude sync" sound name "Ping"' -e 'end run' "$1"; fi; }
PR=$(gh pr view "$BR" --json number -q .number)
MSTATE=UNKNOWN
for i in 1 2 3 4 5 6; do
  MSTATE=$(gh pr view "$PR" --json mergeable -q .mergeable)
  [ "$MSTATE" != "UNKNOWN" ] && break
  sleep 5
done

if [ "$MSTATE" = "MERGEABLE" ]; then
  gh pr merge "$PR" --squash --delete-branch
  MSG="HEVY catalog-scrape: PR #$PR auto-merged to main"
  echo "$(date '+%F %T') $MSG" >> "$LOG"
  banner "$MSG" "Claude sync" Ping
elif [ "$MSTATE" = "CONFLICTING" ]; then
  # Confirmed conflict: DO NOT merge. Leave PR open and raise the full, unmissable alert.
  MSG="HEVY catalog-scrape: PR #$PR has a MERGE CONFLICT with main -- needs manual resolution (gh pr merge $PR)."
  echo "$(date '+%F %T') ALERT: $MSG" >> "$LOG"
  banner "$MSG" "!! HEVY cron -- ACTION NEEDED" Sosumi
  banner "$MSG" "!! HEVY cron -- ACTION NEEDED" Sosumi
  say "Heads up. The Hevy catalog scrape hit a merge conflict and needs you to resolve it." || true
  nohup osascript -e 'on run argv' -e 'display dialog (item 1 of argv) with title "!! HEVY cron -- merge conflict" buttons {"OK"} default button 1 with icon caution giving up after 600' -e 'end run' "$MSG" >/dev/null 2>&1 &
else
  # Inconclusive (still UNKNOWN): don't force it. Leave PR open, softer heads-up.
  MSG="HEVY catalog-scrape: PR #$PR left OPEN -- auto-merge inconclusive, please merge manually."
  echo "$(date '+%F %T') $MSG" >> "$LOG"
  banner "$MSG" "HEVY cron -- review needed" Ping
fi
```

## 5. Cleanup (only if a worktree was created)

```
cd "$MAIN"
git worktree remove "$WT" --force
git branch -D "$BR" 2>/dev/null || true
git fetch origin --prune
```

## 6. If there were NO code changes
Do NOT open a PR and do NOT create a worktree. Just report in your final output what the scrape found (e.g. "catalog unchanged; current detected version still X").

## Guardrails
- NEVER leave uncommitted changes to TRACKED files under $MAIN. Refreshing the gitignored /data cache in MAIN is expected and fine.
- All code edits go in the worktree, delivered as a PR, then auto-merged unless it conflicts. Never force-push main. Do not resolve conflicts unattended — the loud alert defers that to the user.
- Do not commit secrets, /data, or /scripts.
- If any step fails, run the step 5 cleanup if a worktree was created, and report the failure.
