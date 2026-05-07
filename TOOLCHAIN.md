# TOOLCHAIN.md — Mac-less setup for SPIKE-01

This file documents the **exact steps run, in order, with version numbers**
to stand up the spike toolchain on a Windows host with no Mac. The CI macOS
runner does the iOS builds; the iPhone runs the resulting `.ipa` via
Sideloadly; `idevicesyslog` pulls measurement logs back over USB.

> Re-run anything in here from scratch and the spike should be reproducible.
> If a step doesn't work, fix the step, don't paper over it in this doc.

## 0 — Verified host environment

| Tool / fact         | Value                                  | Where checked |
|---                  |---                                     |---            |
| OS                  | Windows 11 Home 10.0.26200             | `winver`      |
| Shell               | PowerShell 5.1 + Git Bash (optional)   | `$PSVersionTable` |
| Git                 | install from [git-scm.com](https://git-scm.com) — confirm `git --version` ≥ 2.40 | `git --version` |
| Node.js             | 22.x (already installed: `22.16.0`)    | `node -v`     |

Node is only used to (re)generate `tests/fixtures/synthetic.jsonl`. CI runs
`spike/analyze.py` under Python 3.12 on `ubuntu-latest`; you do not need
Python locally for normal operation, but a local install lets you iterate
the analyzer without pushing.

```powershell
winget install Python.Python.3.12 -e --accept-source-agreements --accept-package-agreements --silent
py --version    # expect Python 3.12.x
py spike\analyze.py --strict tests\fixtures\synthetic.jsonl
# Last lines should print 'Verdict: PASS' and exit 0.
```

> Note: `analyze.py` reconfigures stdout to UTF-8 at startup so the
> ≤ / ✅ / ❌ glyphs in the report don't trip the Windows cp1252 console.

## 1 — Swift toolchain on Windows (for editing only)

We don't compile iOS targets locally — the GitHub Actions `macos-14` runner
does. The Windows Swift toolchain gives VS Code a real LSP so editing the
Swift sources isn't blind typing.

1. Install via `winget`:

   ```powershell
   winget install --id Swift.Toolchain -e
   ```

   Or download the installer from <https://www.swift.org/install/windows/>.
   Pin the latest **6.x** release branch.

2. Confirm:

   ```powershell
   swift --version
   ```

   Expect a line like `Swift version 6.x (swift-6.x-RELEASE)`.

3. Add `%LOCALAPPDATA%\Programs\Swift\Toolchains\<version>\usr\bin` to PATH if
   the installer didn't.

## 2 — VS Code + Swift extension

1. Install VS Code from <https://code.visualstudio.com/> (`winget install --id Microsoft.VisualStudioCode -e`).
2. Install extensions:
   - `swiftlang.swift-vscode` (official Swift LSP)
   - `redhat.vscode-yaml`
   - `zxh404.vscode-proto3` (proto3 syntax)
   - `ms-vscode.makefile-tools` (optional, for the future `Makefile`)
3. In settings, point the Swift extension at the toolchain installed in §1
   (`Swift › Path: Toolchain`).

## 3 — xcodegen (used remotely on the Actions runner)

No Windows install needed. The `ios` job in `.github/workflows/ci.yml` runs:

```bash
brew install xcodegen
xcodegen --version
```

When session 3 lands the iOS target, the same job will run
`xcodegen generate --spec ios/QuietSpike/project.yml` followed by
`xcodebuild -scheme QuietSpike -destination 'generic/platform=iOS' build`.

## 4 — Sideloadly (install builds on the iPhone)

1. Download Sideloadly for Windows from <https://sideloadly.io>. Confirm version
   ≥ 0.8.x.
2. Install iTunes (Sideloadly needs Apple's USB drivers): Microsoft Store →
   "iTunes" → install.
3. Plug in the iPhone over USB; trust the computer.
4. Once the CI job produces an unsigned `.ipa` artifact, drag it into Sideloadly,
   sign with the free Apple ID enrolled in §6, install to the device.

> **Free-tier limit:** apps signed with a free Apple ID expire after 7 days.
> The spike measurement runs are short — re-sign and reinstall when the cert
> expires. Do **not** pay for a developer account before the spike outcome
> is known.

## 5 — libimobiledevice (`idevicesyslog`) on Windows

`idevicesyslog` streams the iPhone's syslog over USB so the JSONL latency
log emitted by `Logger` lands on the dev machine in real time.

1. Easiest route: the Scoop or Chocolatey port.

   ```powershell
   # via Scoop (https://scoop.sh)
   scoop install libimobiledevice
   ```

   Or:

   ```powershell
   # via Chocolatey (https://chocolatey.org)
   choco install libimobiledevice
   ```

2. Confirm:

   ```powershell
   idevicesyslog --version
   ideviceinfo -k DeviceName
   ```

3. During measurement runs:

   ```powershell
   idevicesyslog | Select-String "QUIET_LATENCY" > tests\run\ios.log
   ```

   The Swift code emits each measurement as a JSON line prefixed with
   `QUIET_LATENCY` so this filter is exact.

## 6 — Free Apple ID + sideload entitlement

1. If you don't already have one: create an Apple ID at
   <https://appleid.apple.com>.
2. Enroll the same Apple ID at <https://developer.apple.com/account/> as a
   *free* developer (no payment). This unlocks 7-day sideload signing.
3. Note the Apple ID and the device UDID; both go into Sideloadly the first
   time you sign a build.

## 7 — Set up Supabase project

> The spike uses Supabase for managed Postgres + the JWT issuer that
> PowerSync trusts. Free tier is sufficient.

1. Sign up / sign in at <https://supabase.com>.
2. **New project** — name `quiet-spike`, region `us-east-1`, password into
   1Password.
3. Wait for provisioning to finish (~2 min).
4. **SQL editor** → paste the contents of
   `supabase/migrations/0001_capture_items.sql` → **Run**. Verify
   `select * from public.capture_items` returns 0 rows.
5. **Authentication → Users** → "Add user" → email-only, no password
   (we'll use a magic link in the next session). Save the resulting `user.id`
   (UUID) — this is `QUIET_TEST_USER_ID` in `.env`.
6. **Project Settings → API** → copy:
   - `Project URL` → `SUPABASE_URL`
   - `anon` `public` key → `SUPABASE_ANON_KEY`
   - `service_role` `secret` → `SUPABASE_SERVICE_ROLE_KEY`
   Save all three to 1Password under entry **"Quiet — Supabase (spike)"**.

## 8 — Set up PowerSync Cloud sandbox

> PowerSync replicates Postgres rows into the device's local SQLite via the
> mobile SDK. The sandbox is the free dev tier.

1. Sign up / sign in at <https://www.powersync.com> → **New instance** →
   region `us-east-1`. Name `quiet-spike`. Take the default credentials and
   put them in 1Password under **"Quiet — PowerSync (spike)"**.
2. **Connections** → connect to the Supabase project from §7. Use the
   service-role key Supabase generated; PowerSync needs `REPLICATION` to
   tail Postgres logical replication.
3. **Sync rules** → paste the contents of
   `supabase/powersync/sync_rules.yaml` → **Save & Deploy**.
4. **Auth** → choose "Supabase JWT" so PowerSync trusts JWTs Supabase issues.
   Paste the JWT secret from Supabase (Project Settings → API → JWT secret).
5. From the dashboard: copy the instance URL → `POWERSYNC_URL` in `.env`.

## 9 — `.env` round-trip

```powershell
Copy-Item .env.example .env
# fill values from 1Password — one Supabase entry, one PowerSync entry
```

`.env` is `.gitignored`. Never commit secrets. Spike repo is public until
product code lands.

## 10 — Verify CI runs end-to-end (before depending on it)

After the first push to `main`:

1. Open the **Actions** tab on the GitHub repo.
2. Confirm three jobs ran: `lint`, `android (placeholder until session 2)`,
   `ios (verify macos-14 runner)`. All three should be green.
3. The `ios` job is the canary: it proves the `macos-14` runner is reachable
   on a public repo with the free minutes pool **before** session 3 depends
   on it for actual builds.

If `ios` fails: Actions → settings → re-enable, or check that the repo is
still set to public (free-tier macOS minutes require public visibility).

## 11 — Android toolchain (session 2)

The `android/quiet-spike/` Gradle build needs a JDK and the Android SDK.
Local installs are required for on-device measurement (`adb`, sideload,
log pull); CI uses `actions/setup-java` + `gradle/actions/setup-gradle`
on Ubuntu so it doesn't need any of this.

1. **JDK 21 (Temurin).** Required by AGP 8.7+.

   ```powershell
   winget install EclipseAdoptium.Temurin.21.JDK -e --accept-source-agreements --accept-package-agreements --silent
   java -version    # expect openjdk version "21.x"
   ```

   Set `JAVA_HOME` if Gradle complains it can't find the JDK
   (`C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot`).

2. **Android Studio (Ladybug or newer).** Provides the SDK manager,
   Compose preview, and the platform-tools (`adb`).

   ```powershell
   winget install Google.AndroidStudio -e --accept-source-agreements --accept-package-agreements
   ```

   First run: open Android Studio → SDK Manager → install
   - Android SDK Platform 35 (`compileSdk = 35`)
   - Android SDK Build-Tools 35.x
   - Android SDK Platform-Tools (`adb`)
   - Android SDK Command-Line Tools (latest)

3. **Platform Tools standalone (alternative to step 2 if you prefer
   not to install Android Studio).**

   ```powershell
   winget install Google.PlatformTools -e --accept-source-agreements --accept-package-agreements
   adb --version
   ```

4. **Pixel 6a setup.**

   - Settings → About phone → tap **Build number** 7× to enable
     Developer options.
   - Settings → System → Developer options → enable **USB debugging**.
   - Plug into USB; the first `adb devices` call triggers an
     "Allow USB debugging from this computer?" dialog on the phone —
     accept and tick "Always allow."

   Verify:

   ```powershell
   adb devices    # should list one device, status "device" not "unauthorized"
   ```

5. **Build the spike target.**

   ```powershell
   cd android\quiet-spike
   .\gradlew.bat :app:assembleDebug
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   adb shell am start -n app.quiet.spike/.CaptureActivity
   ```

   `.env` must exist at the repo root with all six values set, or
   `BuildConfig` keys come back empty and the sandbox sign-in/upload
   silently no-ops (local capture and latency logging still work).

6. **Pull latency logs after a scenario run.**

   ```powershell
   adb pull /sdcard/Android/data/app.quiet.spike/files/latency.jsonl `
            tests\run\android-A-B-$(Get-Date -Format yyyyMMdd).jsonl
   py spike\analyze.py --strict tests\run\android-A-B-*.jsonl `
            > spike\results\android-A-B-$(Get-Date -Format yyyyMMdd).md
   ```

## 12 — Regenerating the synthetic fixture (rare)

```powershell
node tests\fixtures\_generate_synthetic.mjs   # writes tests\fixtures\synthetic.jsonl
node tests\fixtures\_verify_targets.mjs       # asserts all targets pass
```

The committed fixture is what CI runs `analyze.py` against; regenerate only
if you change the target rules in `analyze.py` or the row structure in the
generator.
