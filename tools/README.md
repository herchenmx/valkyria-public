# tools/

Local helpers for the Mac. Nothing here is used by the build or by CI.

## git-autosync — keep the local clone current

`SessionStart` / `SessionEnd` hooks only sync at session boundaries, so the Mac
goes stale whenever no Claude session is running. This runs `git fetch --prune`
every 2 minutes and fast-forwards when it is safe to.

It refuses to act on a dirty worktree, a diverged branch, a detached HEAD, a
branch with no upstream, or an unreachable remote. It only ever fast-forwards,
so nothing is auto-merged and uncommitted work is never touched.

### Install

Run from the repo root. The `sed` fills in absolute paths because launchd does
not expand `~` or `$HOME`.

```sh
chmod +x tools/git-autosync.sh
sed "s|__REPO__|$PWD|g" tools/com.hevy.gitsync.plist \
  > ~/Library/LaunchAgents/com.hevy.gitsync.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.hevy.gitsync.plist
```

`launchctl load` also works but is the deprecated spelling and reports almost
every failure as `Load failed: 5: Input/output error`, naming neither the file
nor the reason. Prefer `bootstrap`, which gives a real message.

Once installed it starts automatically at every login. There is nothing to
re-run after a reboot.

### Check it

```sh
launchctl print gui/$(id -u)/com.hevy.gitsync   # full state, exit codes
launchctl list | grep gitsync                   # 0 in the middle column = last run OK
tail -f .claude/sync.log                         # what it actually did
```

The log is deliberately quiet: at a 2-minute interval it writes only when
something changes, so a silent log means "already up to date", not "broken".
To prove it is alive, check `/tmp/com.hevy.gitsync.out` or watch the log while
pushing a commit from elsewhere.

### Uninstall

```sh
launchctl bootout gui/$(id -u)/com.hevy.gitsync
rm ~/Library/LaunchAgents/com.hevy.gitsync.plist
```

Deleting the file is what makes it permanent. `bootout` alone lasts only until
the next login, because launchd re-reads the directory then.

### If it will not load

```sh
plutil -lint ~/Library/LaunchAgents/com.hevy.gitsync.plist
```

That is the decisive check: it either reports `OK` or names the parse problem.
Note the plist deliberately contains no XML comments — `--` is illegal inside
one, and a malformed plist is rejected with the same opaque I/O error as a
permissions problem, which makes the two impossible to tell apart.

If `plutil` says OK but loading still fails, clear any half-registered job
first, then bootstrap again:

```sh
launchctl bootout gui/$(id -u)/com.hevy.gitsync 2>/dev/null
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.hevy.gitsync.plist
```

## Debug webhook — see what actually goes to Hevy

Off by default. Set `HEVY_DEBUG_WEBHOOK_URL` and both apps mirror every
workout write — private v2 `POST`, public v1 `POST`/`PUT`, the resume `DELETE`,
and the private workout `GET` that resume depends on — to that URL as JSON:
method, path, HTTP status, timing, and the request **and response** bodies.

It exists because the watch's release build is non-debuggable and `adb logcat`
is useless when the failure happens at the gym.

Set `HEVY_DEBUG_WEBHOOK_URL` in **two** places, matching the two ways an APK
gets built:

| where | covers |
|---|---|
| `secrets.properties` in the repo root | local `./gradlew assembleRelease` builds |
| Actions secret of the same name | the published APKs the install helpers use |

The published one is what matters for `hevyinstallwatches` / `hevyinstallphone`
— those install what CI built, so a URL only in your local
`secrets.properties` changes nothing about them.

It lives in secrets rather than tracked config on purpose: these bins expire
and go dead, and rotating one should be an edit in GitHub's settings plus a
re-run, not a code change and a republish.

Turning it off is the same move — clear the secret, re-run the workflow. Worth
doing once the bug it was added for is closed, rather than leaving workout data
flowing to a bin nobody is watching.

The workflow log says which state the APKs were built in:

```
debug: HEVY_DEBUG_WEBHOOK_URL set -- APKs WILL mirror workout writes
```

so you never have to guess whether a given build is mirroring.

Open the webhook URL in a phone browser, do the resume, read the entry. Each
one is tagged `watch` or `phone` and carries the commit, so both devices can
be compared in a single feed.

**No headers are ever sent** — not the `Authorization: Bearer` token, not
`X-Api-Key`, not the public `api-key`. `DebugWebhook.send()` has no parameter
one could be passed through, and a test pins the payload's field set so adding
one fails the build. `/auth/refresh_token` is not matched either, so
credential-bearing bodies are never mirrored.

Workout bodies *are* sent, so this is your training data going to whoever runs
that URL. Use one you control, and blank the value when you're done.

## hevy-shell.sh — install helpers

Source it once from `~/.zshrc` so a `git pull` updates the functions:

```sh
echo 'source ~/Projects/hevy-wear-os-sdk28/tools/hevy-shell.sh' >> ~/.zshrc
source ~/.zshrc
```

The normal run needs no Gradle at all — it installs the APKs CI already built
and published:

```sh
hevycheck             # is the release the same commit as main?
hevyinstallwatches    # both watches: install + stamp the commit + disconnect
# connect the phone, then
hevyinstallphone      # companion: install + disconnect
```

| function | does |
|---|---|
| `hevycheck` | reports, per app, whether `main` and that app's published release are the same commit |
| `hevyinstallwatches [--local] [--force]` | connects both watches, installs, stamps the commit hash, disconnects |
| `hevyinstallphone [--local] [--force]` | installs the companion on the one attached non-watch device |

The watch and the companion each have their **own** rolling release
(`watch-latest` / `companion-latest`), so they are versioned and downloaded
independently and can sit at different commits.

Both install functions **refuse to run unless that app's release is main** — the
same condition `hevycheck` reports, enforced rather than trusted, so a stale
release cannot be installed by forgetting to look. Each downloads only its own
app's asset (from its own tag) into `~/.cache/hevy-apks/<sha>/`, so a repeat
install is instant.

When an app's release lags main they don't just refuse — they ask:

```
❌ watch release is 0db47a0 but main is 319a444 — CI has not published main yet.
Build and install the LOCAL version instead (:app:assembleRelease)? [y/N]
```

Answering `y` runs the right Gradle task for that device and installs the
result. So there is no command to remember: run the function, answer the
question. `n` installs nothing.

`--local` skips straight to that path without asking. Either way an APK older
than the sources it is built from is refused, since stamping a build with a
commit whose code it does not contain is worse than not stamping.

The watches sit on different addresses depending on the network, chosen from
the Mac's own subnet:

| subnet | shiner | ray |
|---|---|---|
| `192.168.2.*` (parents') | `.105` | `.107` |
| anything else (home) | `.149` | `.153` |

Every prompt only appears on a terminal. Piped or scripted, the functions
refuse rather than hang waiting for input that will never come.

### A device that already has the build is skipped

```
⏭  shiner already at ee959af — skipped (stamp refreshed)
✅ ray installed + stamped ee959af
```

So running `hevyinstallwatches` twice, or after only one watch was awake, does
no redundant work. `--force` reinstalls regardless.

The check is a hash of the APK, not a commit read back off the device — see
"Things that are not obvious" for why the device cannot be asked. `adb install`
stores the APK verbatim at `/data/app/<pkg>-<n>/base.apk`, so comparing that
file's `md5sum` to the local one's answers the question exactly, for published
and local builds alike. If the hash can't be read for any reason the skip is
simply disabled, which costs a redundant install rather than skipping one that
was needed.

A skip still re-sends the commit stamp. Matching bytes prove the *APK* is
right, not that the caption is — an install done by hand, or one whose
broadcast was dropped, leaves the two disagreeing.

### A failed device doesn't stop the others

Each watch is installed independently, so a watch that is asleep, off the
network, or rejects the install doesn't prevent the other one from being
done. The failures are collected and offered back at the end:

```
── shiner (192.168.2.105:5555) ───────────
❌ shiner install failed

── ray (192.168.2.107:5555) ──────────────
✅ ray installed + stamped ee959af

⚠️  failed on: shiner
Try again on shiner? [y/N]
```

Answering `y` retries only what failed — a watch that already succeeded is
never revisited, so a retry can't undo work that went through. It keeps
offering until everything passes or you answer `n`, which exits non-zero.
`hevyinstallphone` does the same for its single device.

### Things that are not obvious

- **The watch cannot be asked which commit it is running.** The release build
  is `isDebuggable = false`, so `run-as com.example.hevywatch` is refused and
  the SharedPreferences holding the stamp can't be read; `am broadcast` prints
  `result=0` whether or not a receiver handled it; and `versionName`/
  `versionCode` are static (`"1.0"` / `1`), so `dumpsys package` describes
  every build identically. That is why the skip check hashes the APK instead —
  it needs nothing from the app at all. To read the commit with your eyes,
  use the caption at the bottom of the watch's Settings screen.
- **The companion has no commit stamp.** No `CommitInfoReceiver`, so there is
  nothing to stamp on the phone — but the APK hash check works there too, so
  it is still skipped when it is already current.
- **`path` is a reserved variable name in zsh**, tied to `$PATH`. A `local
  path` inside a function blanks `PATH` for the rest of that function, and the
  symptom is baffling: zsh caches commands it has already run, so the ones used
  earlier keep working while the next new one reports "command not found". Two
  helpers here deliberately avoid the name.
- **Both APKs declare the same `applicationId`** (`com.example.hevywatch`),
  which the Wear message bridge requires. Installing the wrong one at a device
  would replace the right app rather than fail, so both functions check
  `ro.build.characteristics` before installing.
- The broadcast passes `-f 0x00000020` *and* launches the app first: a
  freshly-installed app is in the STOPPED state and silently drops broadcasts,
  and the explicit `-n` component is required because a bare `-a` is dropped.
