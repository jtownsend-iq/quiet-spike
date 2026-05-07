# Session 2 — what landed, what is blocked

**Date:** 2026-05-06
**Goal of session:** build the Android half of SPIKE-01 and collect
scenarios A and B on a Pixel 6a.
**Outcome:** Android target scaffolded, CI rewired, schema-sync guard
in place. Three blockers remain; **all three need your hands** because
they require account creation, secret entry into 1Password, or a
physical device — Claude is rule-bound against doing any of those on
your behalf. None of them block each other; you can run them in any
order.

---

## ✅ Landed in this commit

1. **Precondition A — local Python.** Installed Python 3.12.10 via
   `winget`. `py spike\analyze.py --strict tests\fixtures\synthetic.jsonl`
   now prints `Verdict: PASS` and exits 0. Patched `spike/analyze.py`
   to reconfigure stdout to UTF-8 at startup so the ≤ / ✅ / ❌ glyphs
   don't trip Windows cp1252.
2. **DoD #1 — `android/quiet-spike/` scaffolded.** Compose, single
   Activity (`CaptureActivity`), single `BasicTextField`. Locked design
   tokens in `QuietTheme.kt`. Field contract verbatim from CLAUDE.md:
   placeholder "What just landed.", autocorrect off, IME action Send →
   submit, Esc clears focus, the empty field is the receipt — no toast,
   no animation, no spinner, no haptic, no sound. Tap target ≥ 56 dp,
   body 16 sp / 26 sp line-height, single accent `#1F3A5F` on the focus
   underline only, 1 dp hairline border weight. Reduce-motion is moot
   because there are no animations to gate.
3. **DoD #2 — SQLDelight wired against `schema/capture.sql`.**
   `:app:copyCaptureSchema` copies it into the SQLDelight source set
   verbatim on every build. The target `.sq` is `.gitignore`d so the
   "do not hand-edit" rule is structural. `:app:verifySchemaInSync`
   sha256-diffs the two files; CI fails on drift. Both tasks are
   bound to `preBuild` and to the SQLDelight generation phase so a
   clean clone builds without any manual setup.
4. **DoD #3 — PowerSync + Supabase Kotlin SDKs integrated.** `.env`
   values surface via `BuildConfig` — never in source. The gradle
   script reads `.env` at the repo root; CI synthesises one from
   GitHub Actions secrets. Hardcoded test user; auth is one
   `signInWith(Email)` call. `SyncStack.kt` owns the Postgres connector
   and PowerSync database; `SyncSchema.kt` mirrors the table.
5. **DoD #4 — latency harness.** `LatencyLog.kt` writes one JSON line
   per measurement to `getExternalFilesDir(null)/latency.jsonl` —
   resolves to `/sdcard/Android/data/app.quiet.spike/files/latency.jsonl`.
   Shape matches `spike/analyze.py` and `tests/fixtures/_verify_targets.mjs`
   exactly. `device_id` is a UUID stored in EncryptedSharedPrefs.
   Brackets are explicit and named: `t_keystroke` (first-char →
   next frame), `t_local` (Send → SQLite commit + frame), `t_e2e`
   (Send → Postgrest visibility ACK), `t_converge` stubbed for session 4.
6. **DoD #5 — CI replaced.** Removed the placeholder. New `android`
   job: `actions/setup-java@v4` (Temurin 21), `android-actions/setup-android@v3`
   (platforms;android-35 + build-tools;35.0.0 + platform-tools),
   `gradle/actions/setup-gradle@v4`, then `:app:copyCaptureSchema` →
   `:app:verifySchemaInSync` → `:app:assembleDebug`. Secrets come
   from `${{ secrets.* }}`; if any are unset the build still passes —
   the runtime sandbox call simply no-ops with a `Log.w`. The ios
   placeholder check is unchanged (still trips when ios/ scaffolds).
7. **TOOLCHAIN.md** has new sections: Python 3.12 install, JDK 21,
   Android Studio / Platform Tools alternatives, Pixel 6a setup, and
   the `adb pull` recipe for the latency log.

---

## ⛔ Blocked on you (Precondition B)

**Stand up Supabase + PowerSync sandbox** per `TOOLCHAIN.md` §7–§9.
~10 minutes total; documented step-by-step. Reasons Claude can't do
this:

- **Account creation is forbidden** by the agent safety rules — you
  must sign up at <https://supabase.com> and <https://www.powersync.com>
  yourself.
- **1Password is yours alone** — keys must land in your vault, not
  anywhere I can read them back.

After you finish, the curl in `TOOLCHAIN.md` §7 step 5 should return
`[{"count":0}]`. That's the "sandbox is alive" signal. Without this,
the Android build *succeeds* but `signInIfNeeded()` logs a warning and
no rows reach Postgres — only `t_keystroke` and `t_local` get recorded.

---

## ⛔ Blocked on you (Precondition C)

**Create the public GitHub repo and push.** Same reason — account
creation is yours. Concrete steps:

1. Create a public repo named `quiet-spike` (or whatever you prefer,
   adjust the README accordingly) at <https://github.com/new>. Don't
   initialise it; we have a single local commit to push.
2. From `Projects/App Development/quiet/`:

   ```powershell
   git remote add origin git@github.com:<you>/quiet-spike.git
   git push -u origin main
   ```

3. In Settings → Secrets and variables → Actions, add the six secrets
   the workflow expects (names match `.env.example`):
   `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `POWERSYNC_URL`,
   `QUIET_TEST_USER_EMAIL`, `QUIET_TEST_USER_PASSWORD`,
   `QUIET_TEST_USER_ID`.

4. Open the **Actions** tab. The expected three jobs:

   - `lint` — `analyze.py --strict` against the synthetic fixture, etc.
     This was already green pre-session and stays green.
   - `android (assembleDebug + verifySchemaInSync)` — **the new gate.**
     Confirms the wrapper boots, the schema sync guard works, and the
     Compose + SQLDelight + PowerSync + Supabase dependency tree
     resolves. ~3–5 minutes on cold cache.
   - `ios (verify macos-14 runner)` — unchanged from session 1; the
     macos-14 canary that has to be green before session 3 lands real
     iOS code.

If `android` fails: the assembleDebug error is almost always either
(a) a stale dependency version in `gradle/libs.versions.toml` —
PowerSync 1.0.0-BETA29 and supabase-kt 3.0.2 may have shifted since
the spec was written, or (b) the Android SDK didn't install
successfully on the runner. Both are quick fixes and are session-3-fix
material if they happen, not blockers for measurement.

---

## ⛔ Blocked on you (the actual measurement)

Scenarios A and B can only run on a real Pixel 6a (or your nearest
LTE-capable Android — emulator skews `t_local` because the host
filesystem doesn't fsync the way an Android NAND does). You'll need:

```powershell
# After Android Studio / Platform Tools is on PATH and the Pixel is
# authorised over USB:
cd android\quiet-spike
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n app.quiet.spike/.CaptureActivity

# Run scenario A (200 captures, foreground, "the Patel matter — read
# the deposition before Thursday"). The empty field is the receipt
# between captures.
# Then scenario B (200 captures, force-quit between each via Recents).

adb pull /sdcard/Android/data/app.quiet.spike/files/latency.jsonl `
         tests\run\android-A-B-$(Get-Date -Format yyyyMMdd).jsonl
py spike\analyze.py --strict tests\run\android-A-B-*.jsonl `
         > spike\results\android-A-B-$(Get-Date -Format yyyyMMdd).md
git add -f tests\run\android-A-B-*.jsonl spike\results\android-A-B-*.md
git commit -m "SPIKE-01 day 4 — Android scenarios A & B measured"
```

The harness writes `t_keystroke` and `t_local` from the Compose path
unconditionally; `t_e2e` only logs if Precondition B is also done
(otherwise the Postgrest ACK poll silently times out at 15 s and that
sample is dropped from the file).

Scenario A + B alone gate the Android verdict on `t_keystroke`,
`t_local`, and (if B is done) `t_e2e`. The `t_converge` cell in the
analyze.py table will be empty — that's session 4 (D scenario,
two-device, after iOS lands).

---

## Session-3 reading order (when iOS work starts)

Nothing in this session changes the iOS stack — ADR-001 still says
Swift + SwiftUI + GRDB, ADR-002 still says PowerSync. The only
artifact session 3 should consume from session 2 is the JSON shape
contract (`device_id`, `scenario`, `metric`, `value_ms`, `client_seq`,
`wall_clock`) — see `LatencyLog.kt` for the canonical implementation.

If the Android `t_local` lands above 500 ms in scenario A or above
~1.0 s in scenario B (cold start), session 3 should hold and the
**ADR-001 split-native decision reopens** — that's the contract the
spike is here to enforce. Likewise if `t_e2e` P95 > 6 s once B is
configured.
