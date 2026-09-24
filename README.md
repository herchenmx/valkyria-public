# valkyria

A custom Wear OS workout logger for [Hevy](https://www.hevyapp.com/), plus a
phone companion app. It replaces the official Hevy watch app on an older
Wear OS 2 watch (Kate Spade Scallop 2, API 28) and adds features Hevy's own
watch app doesn't have: automatic progressive overload and warmup-set
prescription.

Sideloaded only. Not on the Play Store.

> In-source names are still `Hevy*` (e.g. `HevyApp`, `HevyApiClient`).
> "valkyria" is the user-facing app name; "Hevy" is the backend it talks to.

## Modules

| Module | What it is |
|---|---|
| `app/` | Watch app. Logs workouts set-by-set, posts them to Hevy. |
| `companion/` | Phone app. Logs you in to Hevy and pushes the token to the watch. |
| `core/` | Pure-JVM shared constants (message paths, PO and warmup tables). |

## Docs

- [PRD-WATCH-APP.md](PRD-WATCH-APP.md): how the watch app works
- [PRD-COMPANION-APP.md](PRD-COMPANION-APP.md): how the companion app works
- [tools/README.md](tools/README.md): local Mac helpers (not used by CI)

## Build

1. Copy `secrets.properties.example` to `secrets.properties` and fill it in.
   Each key is explained in the example file.
2. Build:

```sh
./gradlew :app:assembleRelease         # watch
./gradlew :companion:assembleRelease   # phone
```

Needs JDK 21 (Gradle daemon) and JDK 11 (`:core` toolchain), plus the
Android SDK (platform 36).

Do not bump the watch's `minSdk` or `targetSdk` above 28. See the comments in
`app/build.gradle.kts`.

## About this repo

This is a public snapshot of a private working repo. CI workflows, releases
and build secrets are not included, and Hevy's web client key in
`HevyAuthApi.kt` is replaced with a placeholder.

`api-versions/` holds the Hevy API version the apps should send. The companion
fetches `api-versions/active.json` on start.
