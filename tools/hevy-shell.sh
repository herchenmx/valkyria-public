# Hevy shell helpers. Source this from ~/.zshrc:
#
#     source ~/Projects/hevy-wear-os-sdk28/tools/hevy-shell.sh
#
# Kept in the repo rather than pasted into .zshrc so a git pull updates them.

HEVY_REPO="${HEVY_REPO:-$HOME/Projects/hevy-wear-os-sdk28}"

# The watches sit on different host octets depending on which network they are
# on, so the pairs are chosen from the subnet rather than hardcoded. Always
# port 5555.
#
#   192.168.2.*  -> parents' place
#   anything else -> home
#
# Written as host-octet:name pairs in one array rather than two parallel arrays
# indexed together: bash indexes from 0 and zsh from 1, so an index would name
# the wrong watch depending on which shell sourced this.
_hevy_set_watches() {
    case "$1" in
        192.168.2) HEVY_WATCHES=(105:shiner 107:ray) ;;
        *)         HEVY_WATCHES=(149:shiner 153:ray) ;;
    esac
}

_hevy_pkg="com.example.hevywatch"
_hevy_receiver="com.example.hevywatch/.util.CommitInfoReceiver"
_hevy_action="com.example.hevywatch.SET_COMMIT_HASH"

# Refresh origin/main and the release tag from GitHub. THE EXIT STATUS MATTERS
# and every caller must check it.
#
# Both things compared below — refs/remotes/origin/main and the per-app release
# tags (watch-latest / companion-latest) — are LOCAL refs (`--tags` fetches all
# of them). Off the network they still resolve, to whatever the last
# successful fetch left behind, and two equally stale caches compare equal. So
# an ignored fetch failure does not produce a missing answer, it produces a
# confident wrong one: "✅ safe to install" while main had moved on by four
# commits. Silence here is indistinguishable from agreement, which is why this
# refuses rather than falling back on cached refs.
_hevy_fetch_refs() {
    git -C "$HEVY_REPO" fetch origin main --tags --force --quiet 2>/dev/null
}

# Says what the stale refs happen to say, while making clear it is not an
# answer. No timestamp: `date -r` means "read this file's mtime" in GNU
# coreutils and "treat this as an epoch second" on BSD/macOS, so the portable
# way to print one is to not print one.
_hevy_offline_note() {
    local m w p
    m=$(git -C "$HEVY_REPO" rev-parse --short origin/main 2>/dev/null)
    w=$(_hevy_release_sha watch)
    p=$(_hevy_release_sha phone)
    echo "   cached refs say main=${m:-none} watch-release=${w:-none} companion-release=${p:-none}," >&2
    echo "   but they are only as fresh as the last successful fetch — treat them as" >&2
    echo "   unknown, not as equal." >&2
}

# ── is main the same commit as the published release? ────────────────────────
hevycheck() {
    if ! _hevy_fetch_refs; then
        echo "❌ couldn't reach GitHub — can't tell whether the release is main."
        _hevy_offline_note
        return 1
    fi
    local m w p rc=0
    m=$(git -C "$HEVY_REPO" rev-parse --short origin/main)
    w=$(_hevy_release_sha watch)
    p=$(_hevy_release_sha phone)
    # Each release is checked against main on its own — they can now sit at
    # different commits (a build that changed only one app republishes only it).
    if [ "$m" = "$w" ]; then
        echo "✅ watch     release at $m — safe to install"
    else
        echo "⚠️  watch     main=$m  release=${w:-none}  ($(git -C "$HEVY_REPO" rev-list --count "${w:-$m}..origin/main" 2>/dev/null) commit(s) not yet published)"
        rc=1
    fi
    if [ "$m" = "$p" ]; then
        echo "✅ companion release at $m — safe to install"
    else
        echo "⚠️  companion main=$m  release=${p:-none}  ($(git -C "$HEVY_REPO" rev-list --count "${p:-$m}..origin/main" 2>/dev/null) commit(s) not yet published)"
        rc=1
    fi
    return $rc
}

# The Mac's own LAN address, used only to derive the /24 the watches are on.
_hevy_subnet() {
    local ip
    for iface in en0 en1 en2; do
        ip=$(ipconfig getifaddr "$iface" 2>/dev/null) && [ -n "$ip" ] && break
    done
    [ -n "$ip" ] || return 1
    echo "${ip%.*}"
}

# Guards against the shared applicationId: the watch and companion APKs declare
# the SAME package (deliberately — the Wear message bridge needs it), so pushing
# the wrong one at a device would replace the right app rather than fail.
_hevy_is_watch() {
    adb -s "$1" shell getprop ro.build.characteristics 2>/dev/null | grep -q watch
}

# ── "does this device already have this build?" ──────────────────────────────
# Answered by hashing the APK, not by reading a commit back off the device,
# because the device cannot tell us what it is running:
#
#   - the watch release build is isDebuggable = false, so `run-as` is refused
#     and the SharedPreferences holding the stamp can't be read;
#   - `am broadcast` reports result=0 whether or not anything handled it;
#   - versionName/versionCode are static ("1.0" / 1), so `dumpsys package`
#     names every build alike.
#
# adb install copies the APK to /data/app/<pkg>-<x>/base.apk verbatim, so the
# installed bytes are the bytes we pushed: equal hashes mean equal builds, for
# the published APK and a local one alike, and with no stamp involved.
_hevy_md5_local() {
    # macOS ships `md5 -q`; GNU coreutils ships `md5sum`. Neither is on both.
    if command -v md5 >/dev/null 2>&1; then
        md5 -q "$1" 2>/dev/null
    else
        md5sum "$1" 2>/dev/null | awk '{print $1}'
    fi
}

# Empty on any doubt — no pm path, no md5sum on the device, mangled output.
# Empty means "can't tell", which callers treat as "install it", so a failure
# to read costs a redundant install rather than a skipped one.
_hevy_md5_device() {
    # NOT named `path`: in zsh that is tied to $PATH, so `local path` empties
    # PATH for the rest of the function. It fails bizarrely rather than loudly,
    # because zsh keeps a hash of commands already run — adb still resolves,
    # while tr/sed/head, unused until now, report "command not found".
    local target="$1" apkpath
    apkpath=$(adb -s "$target" shell pm path "$_hevy_pkg" 2>/dev/null \
        | tr -d '\r' | sed -n 's/^package://p' | head -n 1)
    [ -n "$apkpath" ] || return 0
    adb -s "$target" shell md5sum "$apkpath" 2>/dev/null \
        | tr -d '\r' | awk '{print $1}' | grep -Ex '[0-9a-f]{32}'
}

# Prompt + read a y/N answer. On stderr and guarded by a tty check, so piping
# these functions refuses rather than hanging on input that will never arrive.
_hevy_confirm() {
    [ -t 0 ] || { echo "   not a terminal, so not prompting" >&2; return 1; }
    printf '%s [y/N] ' "$1" >&2
    local reply=""
    read -r reply
    case "$reply" in
        [yY]|[yY][eE][sS]) return 0 ;;
        *) return 1 ;;
    esac
}

# ── the published releases ───────────────────────────────────────────────────
# The watch and the companion each have their OWN rolling release, so a build
# can republish one without moving the other. $1 selects which:
#   watch → the `watch-latest` tag, phone → the `companion-latest` tag.
# The tag is pinned to the built commit by the workflow (--target), and the
# asset filename carries it too.
_hevy_release_sha() {
    local tag
    case "$1" in
        watch) tag=watch-latest ;;
        phone) tag=companion-latest ;;
        *) return 1 ;;
    esac
    git -C "$HEVY_REPO" rev-parse --short "$tag^{commit}" 2>/dev/null
}

# Refuses to go on unless the published release IS main. Installing a release
# that lags main means testing code you did not write, while believing you did.
#
# The offline case is a refusal too, and for the same reason: an unreachable
# GitHub means this cannot know what main is, and cached refs agreeing with
# each other is not evidence that they are current. Callers respond by
# offering a local build, which needs no network and is honestly labelled.
_hevy_require_synced() {
    local which="$1" m r
    if ! _hevy_fetch_refs; then
        echo "❌ couldn't reach GitHub — can't verify the release matches main." >&2
        _hevy_offline_note
        return 1
    fi
    m=$(git -C "$HEVY_REPO" rev-parse --short origin/main 2>/dev/null)
    r=$(_hevy_release_sha "$which")
    if [ -z "$r" ]; then
        echo "❌ no published $which release found" >&2; return 1
    fi
    if [ "$m" != "$r" ]; then
        echo "❌ $which release is $r but main is $m — CI has not published main yet." >&2
        return 1
    fi
    echo "→ $which release and main both at $r" >&2
    printf '%s' "$r"
}

# Downloads one module's APK for the published release into a per-commit cache,
# so a repeat install is instant. $1 selects the module (watch/phone); each has
# its own release now, so only that module's asset is pulled from its own tag.
_hevy_fetch_apks() {
    local which="$1" sha="$2" dir="$HOME/.cache/hevy-apks/$2" tag file
    command -v gh >/dev/null 2>&1 || { echo "❌ gh not installed — brew install gh" >&2; return 1; }
    case "$which" in
        watch) tag=watch-latest;     file="hevywatch-$sha.apk" ;;
        phone) tag=companion-latest; file="hevycompanion-$sha.apk" ;;
        *) return 1 ;;
    esac
    if [ ! -f "$dir/$file" ]; then
        mkdir -p "$dir"
        ( cd "$HEVY_REPO" && gh release download "$tag" --dir "$dir" --clobber \
            --pattern "$file" ) >&2 \
            || { echo "❌ download failed" >&2; return 1; }
    fi
    printf '%s' "$dir"
}

# True when any source the APK is built from is newer than the APK itself.
#
# Compares against the sources rather than against HEAD's commit time, which is
# what an earlier version did and which deadlocked: a commit touching only these
# shell helpers makes the APK "older than HEAD", but re-running Gradle then
# reports UP-TO-DATE without rewriting the file, so the check could never be
# satisfied. find -newer needs no stat, so it also sidesteps BSD/GNU differences.
#
# An array rather than a space-separated string expanded unquoted: zsh does not
# word-split unquoted parameters, so `find $dirs` there passes all five paths as
# one argument and finds nothing — which would silently pass every APK as fresh.
_hevy_apk_is_stale() {
    local apk="$1"
    local -a dirs
    case "$2" in
        watch) dirs=(app/src core/src app/build.gradle.kts core/build.gradle.kts gradle/libs.versions.toml) ;;
        phone) dirs=(companion/src core/src companion/build.gradle.kts core/build.gradle.kts gradle/libs.versions.toml) ;;
    esac
    [ -n "$( cd "$HEVY_REPO" && find "${dirs[@]}" -type f -newer "$apk" -print -quit 2>/dev/null )" ]
}

# Offers to build and install a local version when the release lags main, so a
# refusal is a question rather than a dead end with a command to remember.
# Prompts on stderr and builds with output on stderr, because callers capture
# stdout — anything printed there would be taken for a file path.
_hevy_offer_local() {
    local which="$1" task
    case "$which" in
        watch) task=":app:assembleRelease" ;;
        phone) task=":companion:assembleRelease" ;;
    esac

    _hevy_confirm "Build and install the LOCAL version instead ($task)?" \
        || { echo "→ nothing installed (or re-run with --local)" >&2; return 1; }

    echo "→ ./gradlew $task" >&2
    ( cd "$HEVY_REPO" && ./gradlew "$task" ) >&2 \
        || { echo "❌ build failed — nothing installed" >&2; return 1; }
    return 0
}

# Resolves the APK to install: published by default, local build with --local.
# Echoes "<apk path>|<sha>".
#
# The third argument is the release sha the caller already resolved. Without it
# this called _hevy_require_synced a second time — a second `git fetch` and a
# second "→ release and main both at X" line for one install.
_hevy_resolve_apk() {
    local which="$1" mode="$2" presha="$3" sha dir apk
    if [ "$mode" = "--local" ]; then
        case "$which" in
            watch) apk="$HEVY_REPO/app/build/outputs/apk/release/app-release.apk" ;;
            phone) apk="$HEVY_REPO/companion/build/outputs/apk/release/companion-release.apk" ;;
        esac
        [ -f "$apk" ] || { echo "❌ no local APK at $apk — ./gradlew assembleRelease first" >&2; return 1; }
        sha=$(git -C "$HEVY_REPO" rev-parse --short HEAD)
        if _hevy_apk_is_stale "$apk" "$which"; then
            echo "❌ local $which APK is older than its sources — rebuild first" >&2; return 1
        fi
        echo "→ using LOCAL build at $sha" >&2
    else
        sha="$presha"
        [ -n "$sha" ] || sha=$(_hevy_require_synced "$which") || return 1
        # Each module has its own release now, so only this module's APK is
        # fetched — from its own tag (watch-latest / companion-latest).
        dir=$(_hevy_fetch_apks "$which" "$sha") || return 1
        case "$which" in
            watch) apk="$dir/hevywatch-$sha.apk" ;;
            phone) apk="$dir/hevycompanion-$sha.apk" ;;
        esac
    fi
    printf '%s|%s' "$apk" "$sha"
}

# Writes the commit stamp the watch shows at the bottom of its Settings screen.
#
# -f 0x00000020 is INCLUDE_STOPPED_PACKAGES: a freshly installed app sits in
# the STOPPED state and silently drops broadcasts. After an install the app is
# also launched first, because the stamp gives no feedback either way (am
# broadcast prints result=0 whether or not a receiver ran) and the two
# mechanisms are independent. The explicit -n component is required — a bare
# -a is dropped.
_hevy_stamp() {
    local target="$1" sha="$2" launch="$3"
    if [ "$launch" = "launch" ]; then
        adb -s "$target" shell monkey -p "$_hevy_pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
        sleep 2
    fi
    adb -s "$target" shell am broadcast -f 0x00000020 \
        -n "$_hevy_receiver" -a "$_hevy_action" --es hash "$sha" >/dev/null 2>&1
}

# One watch, start to finish. Split out of the loop so a failure can be retried
# on its own without touching the watch that already succeeded.
# Returns 0 for installed or already-current, 1 for anything that failed.
_hevy_install_one_watch() {
    local subnet="$1" entry="$2" apk="$3" sha="$4" force="$5" want="$6"
    local name="${entry##*:}" target="$subnet.${entry%%:*}:5555"
    printf '\n── %s (%s) ─────────────────────────────\n' "$name" "$target"

    if ! adb connect "$target" 2>&1 | grep -qE "connected to|already connected"; then
        echo "❌ $name unreachable — is it awake and is ADB-over-WiFi on?"
        return 1
    fi
    if ! _hevy_is_watch "$target"; then
        echo "❌ $target is not a watch — refusing to install the watch APK"
        adb disconnect "$target" >/dev/null 2>&1
        return 1
    fi

    if [ "$force" -eq 0 ] && [ -n "$want" ] && [ "$(_hevy_md5_device "$target")" = "$want" ]; then
        # Re-stamp anyway, without launching: the APK matching proves the bytes
        # are right, not that the caption is — an install done by hand, or one
        # whose broadcast was dropped, leaves the two disagreeing. Re-sending is
        # cheap and makes the caption trustworthy after a skip.
        _hevy_stamp "$target" "$sha" nolaunch
        echo "⏭  $name already at $sha — skipped (stamp refreshed)"
        adb disconnect "$target" >/dev/null 2>&1
        return 0
    fi

    local out rc
    out=$(adb -s "$target" install -r -d "$apk" 2>&1); rc=$?
    echo "$out"
    if [ "$rc" -eq 0 ]; then
        _hevy_stamp "$target" "$sha" launch
        echo "✅ $name installed + stamped $sha"
        adb disconnect "$target" >/dev/null 2>&1
        return 0
    fi

    echo "❌ $name install failed"
    adb disconnect "$target" >/dev/null 2>&1
    _hevy_explain_install_failure "$out" && return 2
    return 1
}

# Prints an explanation for failures that a retry cannot fix, and returns 0
# when it recognised one so the caller can skip the "try again?" offer.
# Re-running an install that failed for one of these reasons just fails again,
# and being asked implies otherwise.
_hevy_explain_install_failure() {
    case "$1" in
        *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*signatures\ do\ not\ match*)
            echo "   → the installed app was signed with a different key than this APK."
            echo "     Retrying won't help. Either:"
            echo "       • install a local build instead:  hevyinstallwatches --local"
            echo "         (local builds use ~/.android/debug.keystore, the same key"
            echo "          already on the device)"
            echo "       • or fix CI's key so published APKs match, by re-uploading"
            echo "         this Mac's keystore as the DEBUG_KEYSTORE_BASE64 secret:"
            # printf, not echo: zsh's echo expands \n, which split this
            # copy-paste line in half the first time it was printed.
            printf "           base64 -i ~/.android/debug.keystore | tr -d '\\\\n' | pbcopy\n"
            echo "     Uninstalling would also work but wipes the app's data — on the"
            echo "     watch that means the auth tokens pushed from the companion."
            return 0 ;;
        *INSTALL_FAILED_VERSION_DOWNGRADE*)
            echo "   → the device has a NEWER version than this APK. Retrying won't help."
            return 0 ;;
        *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
            echo "   → the device is out of space. Free some and run this again."
            return 0 ;;
    esac
    return 1
}

# ── install the watch APK on both watches ────────────────────────────────────
# Usage: hevyinstallwatches [--local] [--force]
hevyinstallwatches() {
    local mode="" force=0 a
    for a in "$@"; do
        case "$a" in
            --local) mode="--local" ;;
            --force) force=1 ;;
            *) echo "usage: hevyinstallwatches [--local] [--force]" >&2; return 2 ;;
        esac
    done

    local resolved apk sha
    # Decided here, not inside the capture below: read needs the terminal on
    # stdin, and a prompt inside $( ) would be swallowed with the output.
    local presha=""
    if [ "$mode" != "--local" ]; then
        presha=$(_hevy_require_synced watch) || { _hevy_offer_local watch || return 1; mode="--local"; }
    fi
    resolved=$(_hevy_resolve_apk watch "$mode" "$presha") || return 1
    apk="${resolved%|*}"; sha="${resolved##*|}"

    local subnet
    subnet=$(_hevy_subnet) || { echo "❌ couldn't determine this Mac's LAN address"; return 1; }
    _hevy_set_watches "$subnet"

    # Hashed once, not per watch. Empty if md5 is somehow unavailable, which
    # disables the skip rather than skipping on a comparison of "" with "".
    local want=""
    [ "$force" -eq 0 ] && want=$(_hevy_md5_local "$apk")
    echo "→ subnet $subnet.0/24, stamping commit $sha"
    [ "$force" -eq 1 ] && echo "→ --force: reinstalling even where $sha is already on"

    # Retry loop over just the ones that failed. Each pass reports its own
    # failures; a watch that succeeded is never revisited, so answering "y"
    # cannot undo work that already went through.
    local -a pending failedlist stucklist
    pending=("${HEVY_WATCHES[@]}")
    stucklist=()
    while true; do
        failedlist=()
        for a in "${pending[@]}"; do
            _hevy_install_one_watch "$subnet" "$a" "$apk" "$sha" "$force" "$want"
            case $? in
                0) ;;
                # 2 = a failure a retry cannot fix (signature mismatch, no space).
                # Kept out of the retry list so the offer stays honest.
                2) stucklist+=("$a") ;;
                *) failedlist+=("$a") ;;
            esac
        done
        [ "${#failedlist[@]}" -eq 0 ] && break

        local names=""
        for a in "${failedlist[@]}"; do names="$names ${a##*:}"; done
        printf '\n⚠️  failed on:%s\n' "$names"
        _hevy_confirm "Try again on${names}?" || {
            echo "⚠️  finished with failures on$names — see above"
            return 1
        }
        pending=("${failedlist[@]}")
    done

    if [ "${#stucklist[@]}" -ne 0 ]; then
        local stuck=""
        for a in "${stucklist[@]}"; do stuck="$stuck ${a##*:}"; done
        echo ""
        echo "⚠️  not installed on$stuck — see the explanation above; a retry won't fix it"
        return 1
    fi

    echo ""
    echo "✅ both watches at $sha — verify via the caption at the bottom of Settings"
    echo "   (run-as can't read it back: the watch release build is non-debuggable)"
    return 0
}

# ── install the companion APK on the already-connected phone ─────────────────
# Usage: hevyinstallphone [--local] [--force]
hevyinstallphone() {
    local mode="" force=0 a
    for a in "$@"; do
        case "$a" in
            --local) mode="--local" ;;
            --force) force=1 ;;
            *) echo "usage: hevyinstallphone [--local] [--force]" >&2; return 2 ;;
        esac
    done

    local resolved apk sha
    local presha=""
    if [ "$mode" != "--local" ]; then
        presha=$(_hevy_require_synced phone) || { _hevy_offer_local phone || return 1; mode="--local"; }
    fi
    resolved=$(_hevy_resolve_apk phone "$mode" "$presha") || return 1
    apk="${resolved%|*}"; sha="${resolved##*|}"

    # Scalars, not an array: bash and zsh index arrays differently, and getting
    # that wrong here would target the wrong device with a package that shares
    # its applicationId with the watch app.
    local phone="" count=0 d
    while read -r d; do
        [ -z "$d" ] && continue
        if ! _hevy_is_watch "$d"; then
            phone="$d"; count=$((count+1))
        fi
    done < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')

    if [ "$count" -eq 0 ]; then
        echo "❌ no phone attached — connect it and accept the USB-debugging prompt"
        return 1
    fi
    if [ "$count" -gt 1 ]; then
        echo "❌ more than one non-watch device attached — disconnect the extras"
        echo "   so the wrong one can't be targeted (both APKs share an applicationId)"
        return 1
    fi

    echo "→ phone: $phone"

    local want=""
    [ "$force" -eq 0 ] && want=$(_hevy_md5_local "$apk")
    if [ -n "$want" ] && [ "$(_hevy_md5_device "$phone")" = "$want" ]; then
        # Nothing to refresh on a skip here, unlike the watch: the companion has
        # no CommitInfoReceiver, so there is no stamp on the phone at all.
        echo "⏭  companion already at $sha — skipped"
    else
        while ! adb -s "$phone" install -r -d "$apk"; do
            echo "❌ companion install failed"
            _hevy_confirm "Try again on $phone?" || return 1
        done
        echo "✅ companion $sha installed on $phone"
    fi

    case "$phone" in
        *:*) adb disconnect "$phone" >/dev/null 2>&1 && echo "→ disconnected $phone" ;;
        *)   echo "→ $phone is on USB; unplug when ready" ;;
    esac
}
