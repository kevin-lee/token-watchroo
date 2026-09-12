# Token Watchroo

TokenWatchroo - Track your tokens. Get warned before they run out.

A macOS menubar app that shows how much of each AI agent's usage window you have used and warns you before a window runs out. The ring in the menubar fills with the highest usage across your agents. Click it for a card per agent with session and weekly windows, percentages, and reset times. Notifications fire once at 80% and once at 95% of a window, and once more when the window resets.

All business logic is Scala 3 compiled by Scala Native into a static library. A small Swift shell hosts it and owns the menubar item, the menu, notifications, and timers. There is no JVM at runtime.

## Features (v1)

- Agents: Claude Code (subscription plan) and Codex (ChatGPT plan), auto-detected from the credentials the CLIs already store. No sign-in inside the app.
- Menubar ring with four states: normal, warning at 80% (amber), critical at 95% (red), and exhausted (time until reset).
- A card per agent: plan badge (for Claude Code the live plan with the Max multiplier or the Team seat, such as "Max 5x" or "Team Premium"), status pill, session and weekly bars, reset countdowns in your local time zone.
- System notifications, deduplicated per window so a restart never repeats one.
- Codex keeps working offline from its local rollout logs when the usage endpoint fails.
- Menu items: Refresh now, Launch at Login, Quit.

## Limitations (v1)

- The usage endpoints are undocumented and may change. When they do, the card says "Unexpected response" instead of crashing.
- The app never refreshes tokens. When a token expires, run `claude` or `codex` once and the card recovers on the next refresh.
- The bundle is ad-hoc signed, so macOS asks to allow keychain access again after each rebuild. Click "Always Allow" once per build.
- No settings window, thresholds fixed at 80% and 95%, refresh every 60 seconds.
- Cursor, Gemini CLI, API-budget agents, an app icon, notarization, and a DMG are on the roadmap in `.ai/docs/design/token-watchroo-design.md`.

## Requirements

- macOS 14 or later on Apple Silicon (the build is host-architecture only).
- Xcode command line tools with Swift 6 (`swift build`).
- JDK 17 or later and sbt 2 for the build only. LLVM/Clang from Xcode is used by Scala Native.
- Claude Code and/or Codex signed in on this machine.

## Install

Build from source:

```bash
sbt buildApp
open "dist/Token Watchroo.app"
```

`sbt runApp` does both. The app appears in the menubar, not in the Dock.

## Build and test

```bash
# core: hedgehog property tests on the JVM
sbt coreJvm/test

# providers and app: munit on Scala Native
sbt providers/test
sbt app/test

# the static library only
sbt app/nativeLink

# stage the library, build the Swift shell, assemble and ad-hoc sign the bundle
sbt bundleApp

# assemble and open
sbt runApp
```

Set `TW_NETWORK_TESTS=1` to include the libcurl smoke test against example.com.

## Architecture

```
┌──────────────────────────────── Token Watchroo.app ────────────────────────────────┐
│                                                                                    │
│  Swift shell (swift/)                    Scala Native static library (modules/)    │
│  ┌──────────────────────────┐            ┌────────────────────────────────────┐    │
│  │ main thread              │  tw_start  │ token-watchroo-app                 │    │
│  │  NSStatusItem + ring     │──────────► │  exported C API, cats-effect       │    │
│  │  NSMenu + agent cards    │  tw_refresh│  runtime, poll loop, alert state   │    │
│  │  UNUserNotificationCenter│  tw_shutdown                                    │    │
│  └──────────▲───────────────┘            └──────┬────────────────┬────────────┘    │
│             │ callback (JSON envelope)          │                │                 │
│             └──────────────────────────────────┘                │                 │
│                                          ┌───────────────────────▼────────────┐    │
│                                          │ token-watchroo-providers            │    │
│                                          │  keychain, auth.json, libcurl,      │    │
│                                          │  Claude Code and Codex providers    │    │
│                                          └───────────────────────┬────────────┘    │
│                                          ┌───────────────────────▼────────────┐    │
│                                          │ token-watchroo-core (JVM + Native)  │    │
│                                          │  domain model, thresholds, alert    │    │
│                                          │  engine, JSON codecs, parsers       │    │
│                                          └────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────────────────────────┘
```

| Module | Platform | What it holds |
|---|---|---|
| `modules/token-watchroo-core` | JVM and Scala Native, pure | Domain types (refined4s newtypes, Scala 3 enums), status and menubar rules, the alert engine, ISO-8601 parsing, notification copy, jsoniter-scala codecs, the Codex rollout parser. Tested with hedgehog on the JVM. |
| `modules/token-watchroo-providers` | Scala Native | Keychain reader (`/usr/bin/security`), `auth.json` reader, libcurl HTTP client, the Claude Code and Codex providers. Tested with munit. |
| `modules/token-watchroo-app` | Scala Native static library | The exported C API (`tw_start`, `tw_refresh`, `tw_set_config`, `tw_shutdown`), the cats-effect runtime, the poll loop, state persistence. Tested with munit. |
| `swift/` | Swift 6 package | `NSStatusItem`, `NSMenu` with custom card views, notifications, Launch at Login. Renders what the library sends. |

The full design, including the threading and garbage-collector contract between Swift and Scala Native, the JSON contract, and the roadmap, is in `.ai/docs/design/token-watchroo-design.md`.

## Credentials and privacy

- Claude Code: the app reads the `Claude Code-credentials` keychain item through `/usr/bin/security`, the same way TokenEater and CodexBar do, calls `https://api.anthropic.com/api/oauth/usage` with that token, and calls `https://api.anthropic.com/api/oauth/profile` for the plan badge at most once an hour and on Refresh now.
- Codex: the app reads `~/.codex/auth.json` (or `$CODEX_HOME/auth.json`) and calls `https://chatgpt.com/backend-api/wham/usage`. When that fails it reads the newest log under `~/.codex/sessions`.
- Nothing is written back to those files or to the keychain. Tokens never appear in logs or on cards.
- The only file the app writes is `~/Library/Application Support/Token Watchroo/state.json`, which remembers which notifications already fired.
- No telemetry, no accounts, no other network calls.

## License

MIT. See `LICENSE`.
