# Weekly code review (scheduled, unattended)

Paste this into the scheduled task. Only the four `MAIN` / `WORKTREES` lines
need touching if the repo ever moves again.

---

This task runs UNATTENDED on a schedule. The user is not present to answer questions — execute autonomously and make reasonable choices. You MUST do all work in an isolated git worktree and deliver results as a pull request. You MUST get the whole branch green (full tests + release builds) BEFORE opening the pull request, and only then auto-merge — unless it conflicts, in which case leave it open and raise a loud alert. NEVER edit the main working directory.

Every path in this task derives from these two variables. Set them once; change nothing else if the repo moves:

```
MAIN=~/Projects/hevy-wear-os-sdk28
WORKTREES=~/Projects/hevy-worktrees
```

Treat everything under $MAIN as read-only. All edits, tests, and builds happen inside a throwaway worktree under $WORKTREES.

Do not place either path under ~/Downloads, ~/Documents, or ~/Desktop. macOS privacy protection blocks scheduled background jobs from those folders, and the failure looks like a permissions bug on files that are plainly readable when you run them by hand.

## 1. Isolation setup (do this FIRST, before any review or edits)

```
mkdir -p "$WORKTREES"
git -C "$MAIN" fetch origin
TS=$(date +%Y%m%d-%H%M%S)
BR="auto/weekly-code-review-$TS"
WT="$WORKTREES/weekly-code-review-$TS"
git -C "$MAIN" worktree add -b "$BR" "$WT" origin/main
cd "$WT"
```

From here on, everything happens inside $WT. Do not `cd` back into MAIN except for the cleanup step at the end.

### Branch discipline (important)
You are on branch $BR inside the worktree. Every commit the implementation workflow makes commits to $BR; every push goes to origin/$BR — NEVER to main. Your FIRST push must set the upstream: `git push -u origin "$BR"`. All subsequent pushes are plain `git push` (they go to $BR automatically). Never run `git push origin main`, never `git checkout main`, never merge into main from inside the worktree.

## 2. The review
Review the entire codebase — the watch app including its tile, and the companion app including its home-screen widget. Look for:
- prunable code
- gaps in unit tests
- code that could be more streamlined / better / state of the art
- opportunities to make the app faster / less battery-heavy (e.g. preload / cache more)
- other refactoring opportunities
- security vulnerabilities
- UX/UI improvement opportunities
- additional useful features.

Constraints: the watch module targets Wear OS 2.5 / minSdk & targetSdk 28 (Kate Spade Scallop 2 hardware limit) and must stay there. The companion module targets latest stable — never align the two.

## 3. Deliverable
Produce a NUMBERED action plan: for each proposed action give a short description, pros, cons, and risks.

Because the user is not here to pre-approve, IMPLEMENT the changes you judge safe and high-value, inside $WT, following the implementation workflow to its full extent EXCEPT device installs: (1) create/update unit tests, (2) run them, (3) build the release APK(s) for affected modules, (4) update the PRDs (PRD-WATCH-APP.md and/or PRD-COMPANION-APP.md). Do NOT install to any device and do NOT run SET_COMMIT_HASH — the user does device installs manually. Commit to $BR as you go (first push uses `-u`, per Branch discipline).

Leave anything risky or ambiguous OUT of the code and instead list it in the PR body under a "Proposed — not implemented, needs your call" section, with its pros/cons/risks.

## 4. Final verification gate — the WHOLE branch must compile + pass BEFORE the PR is opened
Per-item builds during step 3 are NOT enough: combined changes can interact and break in ways the individual builds didn't catch. Verify the entire branch as a unit and loop until it is green.

This runs BEFORE publishing, deliberately. When the gate sat after `gh pr create`, a run opened its PR and squash-merged it 19 seconds later — far less than the ~9 minutes this command takes — so whatever ran, it was not gating the merge. Ordering it first makes the sequencing enforce the rule instead of relying on the runner to honour it.

1. From $WT, run the full suite (all modules' unit tests + both release APK builds):
   ```
   ./gradlew :core:test :app:test :companion:test :app:assembleRelease :companion:assembleRelease
   ```
   Use `:app:test` / `:companion:test`, NOT `:app:testReleaseUnitTest`. AGP 9.2.1 does not define a `testReleaseUnitTest` task; asking for it fails at task selection before a single test runs. The aggregate `test` task is stable across AGP versions and covers the debug unit tests too.

   This is deliberately the same command `.github/workflows/build.yml` runs on every push, so a green run here predicts a green CI run after merge.
2. If everything passes clean → go to step 5 (publish), then step 6 (merge).
3. If ANYTHING fails (a compile error or a failing test): diagnose the root cause, FIX it in $WT — do NOT delete, `@Ignore`, comment out, or otherwise weaken tests to go green; fix the real cause — then commit and push the fix to the branch:
   ```
   git add -A && git commit -m "Fix: <what you fixed> (pre-merge verification gate)" && git push
   ```
   Then go back to sub-step 1 and re-run the FULL command.
4. Repeat this run → fix → re-run loop until the whole command passes clean. Cap it at 5 fix cycles.
5. If after 5 cycles it still is not green, do NOT merge. Open the PR anyway — the work and the diagnosis are worth keeping, and a red branch with an explanation is more useful than a deleted worktree — then raise the loud alert and skip to step 7 cleanup, leaving the PR open for the user. Mark it so it can never be mistaken for a passing review:
   ```
   cd "$WT"
   git push -u origin "$BR"
   gh pr create --base main --head "$BR" \
     --title "Weekly code review — $TS [RED — DO NOT MERGE]" \
     --body "The pre-merge verification gate did NOT pass after 5 fix attempts, so this was NOT merged.

   Last failure:
   <paste the failing task + assertion/compile error>

   <full numbered action plan, as normal>"

   LOG="$MAIN/.claude/sync.log"
   TN=/opt/homebrew/bin/terminal-notifier
   PR=$(gh pr view "$BR" --json number -q .number)
   MSG="HEVY weekly-code-review: PR #$PR FAILS the build/test gate after 5 fix attempts -- NOT merged, needs manual fixing."
   echo "$(date '+%F %T') ALERT: $MSG" >> "$LOG"
   [ -x "$TN" ] && "$TN" -title "!! HEVY cron -- ACTION NEEDED" -message "$MSG" -sound Sosumi -open "file://$LOG"
   [ -x "$TN" ] && "$TN" -title "!! HEVY cron -- ACTION NEEDED" -message "$MSG" -sound Sosumi -open "file://$LOG"
   say "Heads up. The weekly review could not get the build green and did not merge." || true
   nohup osascript -e 'on run argv' -e 'display dialog (item 1 of argv) with title "!! HEVY cron -- build gate failed" buttons {"OK"} default button 1 with icon caution giving up after 600' -e 'end run' "$MSG" >/dev/null 2>&1 &
   ```
Only continue to step 5 (publish) and step 6 (merge) if the gate went fully green.

## 5. Publish

```
cd "$WT"
git push -u origin "$BR"
gh pr create --base main --head "$BR" --title "Weekly code review — $TS" --body "<full numbered action plan: implemented items with pros/cons/risks, plus the 'Proposed — not implemented' list>"
```

Run `gh pr create` from inside $WT so it infers the repo from the shared remote.

Do NOT reach this step until step 4 has gone green. Pushing the *branch* as you work (step 3) is fine and expected — it is opening the *PR* that is gated, because step 6 merges within seconds of the PR existing. If the gate has not already run by then, nothing stands between an unverified branch and main.

If the review produced NO code changes: do NOT push, do NOT open a PR. Skip to step 7 cleanup and report what you found in your final output.

## 6. Auto-merge with conflict guard
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
  MSG="HEVY weekly-code-review: PR #$PR verified green + auto-merged to main"
  echo "$(date '+%F %T') $MSG" >> "$LOG"
  banner "$MSG" "Claude sync" Ping
elif [ "$MSTATE" = "CONFLICTING" ]; then
  # Confirmed conflict: DO NOT merge. Leave PR open and raise the full, unmissable alert.
  MSG="HEVY weekly-code-review: PR #$PR has a MERGE CONFLICT with main -- needs manual resolution (gh pr merge $PR)."
  echo "$(date '+%F %T') ALERT: $MSG" >> "$LOG"
  banner "$MSG" "!! HEVY cron -- ACTION NEEDED" Sosumi
  banner "$MSG" "!! HEVY cron -- ACTION NEEDED" Sosumi
  say "Heads up. The weekly Hevy code review hit a merge conflict and needs you to resolve it." || true
  nohup osascript -e 'on run argv' -e 'display dialog (item 1 of argv) with title "!! HEVY cron -- merge conflict" buttons {"OK"} default button 1 with icon caution giving up after 600' -e 'end run' "$MSG" >/dev/null 2>&1 &
else
  # Inconclusive (still UNKNOWN, or merge state not confirmable): don't force it. Leave PR open, softer heads-up.
  MSG="HEVY weekly-code-review: PR #$PR left OPEN -- auto-merge inconclusive, please merge manually."
  echo "$(date '+%F %T') $MSG" >> "$LOG"
  banner "$MSG" "HEVY cron -- review needed" Ping
fi
```

## 7. Cleanup

```
cd "$MAIN"
git worktree remove "$WT" --force
git branch -D "$BR" 2>/dev/null || true
git fetch origin --prune
```

Removing the local worktree and local branch is safe: if the PR merged, --delete-branch already dropped the remote branch; if it was left open, the remote branch (and PR) stay intact for you to merge manually — only the LOCAL copies are removed.

## Guardrails
- NEVER modify, create, or delete files under $MAIN directly (the worktree under $WORKTREES is where all edits go). If you catch yourself editing a MAIN path, stop and redo it in $WT.
- Only main is protected from force-push; never force-push main. Do not resolve conflicts unattended — that is exactly what the loud alert defers to the user.
- NEVER merge a branch that hasn't passed the step 4 verification gate green. A red build must never reach main.
- NEVER open the PR before that gate is green. The merge follows the PR within seconds, so an early PR removes the only thing keeping an unverified branch out of main — this is exactly how a run once merged 19 seconds after opening its PR.
- Do not commit secrets. Do not commit the gitignored /data or /scripts contents.
- If any step fails, run the step 7 cleanup before finishing, and report the failure.
