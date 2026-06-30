<p align="center">
  <img src="extras/logo.png" alt="OwnTV" width="360">
</p>

<p align="center">
  <b>Your own IPTV player for Android TV</b><br>
  <sub>Fast · modern · remote-first — bring your own M3U or Xtream sources</sub>
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.3.10-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Compose for TV" src="https://img.shields.io/badge/Jetpack%20Compose-for%20TV-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="Player" src="https://img.shields.io/badge/engines-libmpv%20%2B%20ExoPlayer-FB8C00">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-blue">
  <img alt="Built with the help of AI" src="https://img.shields.io/badge/built%20with-the%20help%20of%20AI-8A2BE2">
</p>

<p align="center">
  <a href="https://github.com/ahXN00/OwnTV/actions/workflows/android.yml">
    <img alt="Android CI" src="https://github.com/ahXN00/OwnTV/actions/workflows/android.yml/badge.svg">
  </a>
</p>

---
> ## 🌊 About this fork — SurfTV
>
> This is a **modified fork of [OwnTV](https://github.com/ahXN00/OwnTV)**, customized to serve as the
> client for **SurfTV** — a self-hosted, pseudo-linear TV system that turns a personal Plex library into
> surfable, channel-style live TV. The upstream OwnTV project is a general-purpose IPTV player; this fork
> adapts it specifically for the SurfTV backend and home setup.
>
> **What's changed in this fork**
> - **Mid-show join** — tuning into a channel drops you into the program already in progress, like real TV,
>   instead of starting it from the beginning.
> - **Per-profile EPG sources** — each profile sees only its own assigned channels and guide, with strict
>   partitioning (no shared fallback).
> - **In-app updates from GitHub Releases** — the client checks this repo's releases and updates itself,
>   no sideloading required after the first install.
> - Ongoing UI, playback, and reliability tuning specific to the SurfTV experience.
>
> **What's still coming**
> - EPG visual pass — guide layout refinements, focus fixes, and more reliable channel surfing.
> - VOD (on-demand) pages and "surf all the time" browsing.
> - A native Music page and pseudo-radio.
> - Audio leveling across channels.
>
> SurfTV is a personal/household project and is **not affiliated with or endorsed by** the upstream OwnTV
> project. All credit for the original player goes to its authors. This fork retains OwnTV's MIT license.


OwnTV is a native **Android TV** IPTV **player** built with Kotlin, Jetpack Compose for TV, and a
**dual playback engine** — **libmpv (FFmpeg)** for movies/series and maximum compatibility, **ExoPlayer
(Media3)** for near-instant Live TV. It's a *player only* — you bring your own Xtream login or M3U playlist
(by **URL or a local `.m3u`/`.m3u8` file** on the device), and OwnTV gives you a fast, modern, remote-first
way to browse
and watch them.

> ⚠️ OwnTV does **not** provide any channels, playlists, subscriptions, streams, or media content.
> You are responsible for adding your own legally accessible sources.

This is an **open-source** project — the code is original (not derived from any other app) and was
**built with the help of AI**. **Contributions are welcome**: clone, build, and test it freely. It
targets **Android TV only** (leanback launcher, D-pad-first UI).

> ### 📖 New here? Read the [**User Guide & Hidden Features →**](extras/USER_GUIDE.md)
> Long‑press to favourite, **Left** for the channel list, the gear button's **compatibility mode**,
> catch‑up from the Guide, startup landing, A/V‑sync — all the remote shortcuts in one place.

---

## 💬 Community

Questions, ideas, bug reports — or just want to follow along? **Join the OwnTV Telegram group:**

### 👉 [t.me/owntvplayer](https://t.me/owntvplayer)

Scan to join from your phone:

<a href="https://t.me/owntvplayer"><img src="extras/telegram_qr_code.jpg" alt="Scan to join the OwnTV Telegram group" width="170"></a>

---

## ✨ Features

### 🎬 Playback (two engines: libmpv + ExoPlayer)
- **Dual-engine design** — **libmpv (FFmpeg)** is the primary player for **movies, series, and any stream
  ExoPlayer can't open**: maximum codec/container compatibility, every audio/subtitle track, and the
  zero-copy 4K/HDR direct path. **ExoPlayer (Media3)** powers **Live TV** — it opens HLS almost instantly,
  so the channel-list preview and full-screen come up with no wait. Live automatically falls back to mpv
  when a stream needs it.
- Plays virtually any codec/container, and exposes **every** audio and subtitle track (not just the
  ones the device can decode).
- **TV-optimized rendering** — the decoder writes frames **directly to the display** (the zero-copy
  pipeline streaming apps use): smooth 4K HDR with the panel's native HDR handling and fast channel
  starts, with subtitles drawn by the app. Software decoding is used automatically as a fallback for
  streams the hardware decoder can't handle.
- Custom TV HUD: scrubbable seek bar, prev/next, audio/subtitle/speed pickers, zoom & aspect modes,
  volume, and auto-hide controls.
- **Channel zapping** — while watching fullscreen with the controls hidden, **D-pad Up/Down** (plus
  **CH+/CH−** and the **media ⏮/⏭** keys) switch channels and wrap around at the ends, so remotes
  without dedicated channel buttons (e.g. Fire TV) can zap too.
- **In-player channel list** — press **Left** (with the controls hidden) to slide a **channel list over the
  video** and pick a channel visually without leaving full-screen; the current one is highlighted, Back or
  Left again closes it.
- **Restores after the screensaver** — a paused movie/episode left long enough for the TV's screensaver is
  brought back **paused at the exact spot**, so pressing Play just resumes instead of doing nothing.
- Large demuxer cache for smooth 4K/8K streams; **HDR passthrough** when the video and TV support it.
- **Surround sound** (**off by default — opt-in**) — leave it off on TV speakers / stereo soundbars for a
  clean stereo downmix; turn it **on** with a 5.1/7.1 receiver to decode **Dolby (AC-3/E-AC-3) and DTS** to
  **multichannel LPCM** over HDMI while 4K/HDR video stays smooth. If a TV claims 5.1 but mis-plays it, a
  built-in safety net auto-falls-back to stereo, so playback never breaks.
- **Image-based subtitles** (PGS/VOBSUB/DVB) on movies & series display on their own layer — keeping the
  video on the smooth zero-copy/HDR path — alongside the usual text (SRT/ASS) subtitles.
- **Closed captions (CC) on Live TV** — channels that embed **CEA-608/708** captions in the video stream
  (e.g. many US channels like HBO/Showtime/Cinemax) now expose a selectable caption track in the **Subtitles**
  menu, instead of showing only "Off".
- **Per-channel compatibility mode** — if a live channel shows artifacts or won't play right on the fast
  engine, the **gear** in the player controls pins that channel to the **mpv** engine. It's **remembered per
  channel**, so it opens cleanly every time after — while every other channel keeps the near-instant start.
- **Stream info overlay** — an **info** button in the player shows a live technical readout of the stream:
  codec · resolution · fps · bit-depth, HDR type, bitrate, decoder (hardware/software · direct), audio
  codec/channels/rate, buffer & dropped frames, and the (credential-masked) source. Works on both engines.
- **Volume boost to 150%** — for movies, series and mpv-played channels, the volume can go **above 100%**
  (Kodi-style amplification, capped at 150% with a soft limiter) for quiet streams.
- **Mini-player / PiP** — dock a movie, episode **or live channel** to a corner and keep browsing
  (it keeps streaming across the whole app); selecting another channel updates the docked window,
  and you can expand or close it.
- **Resume, your way** — movies & episodes remember where you stopped; replaying shows a small
  *"Resume at 23:45?"* prompt, with a setting for **Always / Ask / Never** resume.
- **Auto-play next episode** — episodes roll on automatically (and into the **next season** when one
  finishes), with a Settings toggle for anyone who prefers to pick the next episode themselves.
- **Resume on the right episode** — reopening a series jumps to your **last-watched episode** (right
  season, scrolled into view, tagged *"Last watched"*) instead of always starting at episode 1.
- 📺 **[Complete player design & feature reference →](extras/player.html)** — an interactive Material 3
  mockup documenting the full player HUD, PiP, popup menus, and remote-key mappings.

### 🧭 Browse
- **Home screen with Continue Watching** — a **Home** tab with a hero carousel of your partially‑watched
  movies, episodes and recent live channels (newest first): the selected card shows large with its poster and
  plays a muted preview when focused, and **OK** resumes where you left off — plus a **Favourite Channels**
  rail. On stock Android TV it also feeds the system **"Continue Watching"** (Watch Next) row so you can resume
  from the launcher; on Fire TV / Google TV the in‑app Home is the universal landing.
- **Live TV**, **Movies**, **Series**, **Downloads**, and a full **EPG Guide**. The Live preview pane
  shows the channel's video, now/next/later, and the **real stream resolution** (e.g. `1080p`/`4K`) —
  so a channel named "…4K" that's actually 1080p can't fool you. The preview plays on the **ExoPlayer**
  engine for near-instant pictures while scrolling, and pressing OK **promotes that same stream to
  full-screen** with no reload — instant. (mpv takes over only for streams ExoPlayer can't open.)
- **Fixed, roomy layout** — a stable grid (slim **icon nav** · **full-label category column** · content list ·
  preview) that **never expands or collapses as you move the D-pad**, so nothing jumps around (and it feels
  noticeably faster on lower-end boxes). The profile avatar stays pinned top-left — **click to switch
  profiles**, long-press to change your picture. A fresh **monochrome duotone** Material 3 nav‑icon set (it
  tints with your theme) and **taller, phone-style posters** round out the look.
- Folder rail with section-specific **Favorites** and **History** — **full category names** always shown, with
  a **category search box** to filter hundreds of groups by name. Inline per-folder search and a cross-section
  **global search** too — all TV-style: search bars take focus like any control and only open the keyboard on **OK**.
- **Sort toggle** per section: your **playlist's own order** or **A–Z** (Live TV defaults to playlist order).
  Movies & Series also have a **Grid / List** view toggle — switch the poster wall to a compact list to see
  many more titles at a glance.
- **Customize everything (per profile):** hide, rename & reorder categories (move one step or **jump to the
  top/bottom**, including **hide/show a whole range at once** via long-press); hide & rename channels — and it
  all survives re-syncs.
- **Global search** shows **detailed channel results** — each carries its **category · channel number** so
  near-identical feeds (several "ABC" / "Sky Sports") are easy to tell apart — and can favourite as it finds:
  **long-press a channel result** to add/remove it from Favorites without opening Live TV first.
- **Picks up where you left off** — Live TV reopens on the **category** you last had selected and lands focus
  on the **last channel** you were on. A **per-profile Startup** setting controls where each profile opens:
  **Home**, the **last channel** you watched, or **Live TV on Favorites** (so one profile lands on its
  favourites while another boots straight into its channel).
- Built for scale — tested with ~50k channels / ~168k movies via streaming import and Paging 3.

### 🗓️ EPG
- A full **time × channel guide grid** (XMLTV), plus per-channel **now / next / later** in the Live preview.
- **Catch-up TV (archive)** — for providers that support it (Xtream `tv_archive` / M3U `catchup`),
  watch programmes that **already aired**: the guide extends back in time (up to 7 days), and *Watch from
  start* replays a programme from the archive (seekable). A **Catch-up time** setting (device timezone — the
  default — or a manual UTC offset) handles panels that index their archive in local time.
- **Live rewind (timeshift)** — on a catch-up channel, **rewind the live stream** to re-watch a moment you
  missed and jump back to the live edge, without leaving for the Guide. A scrubbable live timeline plus
  ⏪/⏩ 30-second steps and a **● Live** button, with a "behind live" counter.
- **Smart EPG matching** — an **Auto-match EPG** button links channels to the guide **by name** when
  their tvg-id is missing or wrong (ignoring HD/country tags); confident matches apply automatically,
  the rest go to a quick review list. Or **long-press a channel** to auto-match or pick its guide entry
  manually. All matches are per profile and survive re-syncs.
- **Two-stage guide navigation** — press **Right** on a channel to select its **whole programme row**,
  then **OK** to browse programmes with Left/Right (Up/Down jumps channels at the same time), plus a
  **sort/filter** button (A–Z / Provider / Live TV / Catch-up / **Favorites**) and a **Category** filter
  (with a search box) to view just one group at a time instead of every channel at once.
- **Fast & resilient** — the Guide is **pre-loaded in the background** so it opens instantly; syncs are far
  lighter (only **your** channels' programmes are stored, not the whole feed — public XMLTV feeds often carry
  10–20× more); and a malformed feed or one bad `<programme>` tag no longer aborts the entire guide.
- **Multiple EPG sources** — add any number of XMLTV feeds in **Settings → EPG Sources** (edit /
  delete / re-sync); they merge into the guide. EPG is **opt-in** — importing a playlist doesn't pull its
  guide automatically (which kept imports fast); when you add a guide, the form **pre-fills the playlist's
  own URL** (Xtream `xmltv.php` / M3U `url-tvg`), so it's still one step.
- Clear status: the Guide shows *"Guide loaded: N channels · M programmes · K with catch-up"*, and each
  EPG source shows its own sync state.

### 👥 Profiles
- Multiple profiles with their own favorites/history/resume, optional **PIN locks** (salted hash) and a
  **kids** flag, and a "Who's watching?" launch gate. Sources can be shared between profiles.

### ⬇️ Downloads
- Offline downloads for **movies & episodes** (never Live TV), with pause/resume and a user-chosen
  download folder.

### 🎨 Personalization & settings
- Material 3 theme (AMOLED dark / light / system), **any accent color** — presets, a palette, or an
  exact **hex code** (the whole theme is generated from it) — UI zoom, per-profile avatars.
- **Animations setting** (Full / Reduced / Off) to tone down or disable motion — handy on lower-end TV boxes
  where it can make fast menu navigation feel snappier.
- **Clear watch history** per profile — wipe the recently-watched / *continue watching* rows, **all of it or
  just Live TV / Movies / Series**, without touching playlists, favorites or downloads.
- **Video Player** settings: hardware decoding, default zoom, subtitle size & language, audio sync.
- **Complete Backup & Restore** — one file covers profiles, sources, customizations, **favorites,
  watch history, and resume positions** — and you **choose what to include** on export and what to
  apply on restore; per-source User-Agent, refresh-on-startup, default source.
- **In-app updates** — OwnTV checks GitHub Releases (automatically on startup with a small corner
  status card — toggleable — or manually), shows the full changelog, and installs the new APK right
  on the TV. No browser, no storage permission.

### 🛡️ Robustness
- **Sized for real TVs** — the player's memory budget scales to the device (lean stream buffers on
  low-RAM panels), a decode watchdog blocks 4K/8K software-decode death spirals with a clear error,
  backgrounding releases the stream immediately, and the app sheds caches under memory pressure.
- All player commands run **off the UI thread** — a stalling stream can never freeze the remote (no ANRs),
  and fast preview-scrolling coalesces loads so only the channel you land on is opened.
- **Connection-friendly**: preview → fullscreen reuses the same stream (no reconnect — kind to strict
  1-connection providers), and a dropped live stream **auto-reconnects** before showing an error + Retry.
- **Resilient imports** — a provider that errors on its full Movies/Series list (some return a non-standard
  **HTTP 512** on the giant bulk response) no longer aborts the whole import: it automatically retries that
  section **one category at a time**, and if a section still won't load it keeps your channels rather than
  failing outright. Credentials are never shown in error messages.
- Offline detection with a banner, and friendly, offline-aware error messages on import/sync/guide.

---

## 📸 Screenshots

<table>
  <tr>
    <td align="center"><img src="extras/screenshots/Home.png" alt="Home screen"><br><sub>Home — Continue Watching</sub></td>
    <td align="center"><img src="extras/screenshots/Main_View.png" alt="Main view"><br><sub>Main view</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="extras/screenshots/LiveTV_with_PreviewON.png" alt="Live TV with preview"><br><sub>Live TV — preview playing</sub></td>
    <td align="center"><img src="extras/screenshots/Movies.png" alt="Movies"><br><sub>Movies</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="extras/screenshots/Profile_Selection.png" alt="Profile selection"><br><sub>"Who's watching?" profile gate</sub></td>
    <td align="center"><img src="extras/screenshots/Downloads.png" alt="Downloads"><br><sub>Downloads</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="extras/screenshots/Settings_Main.png" alt="Settings"><br><sub>Settings</sub></td>
    <td align="center"><img src="extras/screenshots/EPG_loaded.png" alt="TV Guide"><br><sub>TV Guide (EPG)</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="extras/screenshots/Settings_Personalization.png" alt="Personalization"><br><sub>Personalization</sub></td>
    <td align="center"></td>
  </tr>
</table>

More in **[extras/screenshots/](extras/screenshots/)** — Live TV (preview off), playlist management,
profiles & sources settings.

---

## 🧱 Tech stack

| Area | Choice |
|------|--------|
| Language | Kotlin 2.3.10 (AGP 9 **built-in Kotlin**, no `kotlin-android` plugin) |
| Build | AGP 9.2.1 / Gradle 9.4.1, KSP2 2.3.9 |
| UI | Jetpack Compose for TV (`androidx.tv:tv-material` 1.1.0), Compose BOM 2026.05.00 |
| Media | **libmpv** (FFmpeg) — `dev.jdtech.mpv:libmpv` · **ExoPlayer/Media3** (Live TV + image subs) |
| Database | Room 2.8.4 + Paging 3.5.0 + FTS4 (WAL) |
| DI | Koin 4.1.1 |
| Networking | OkHttp |
| Images | Coil 3.3.0 |
| Preferences | DataStore |

`minSdk 26`, `targetSdk 36`, `applicationId tv.own.owntv`.

> **Build note:** Kotlin comes from AGP 9's built-in Kotlin (no `kotlin-android` plugin). KSP 2.3.6+
> supports built-in Kotlin, so Room codegen works alongside it; the Compose compiler and KSP track
> Kotlin 2.3.x.

## ⚙️ How it works (backend)

- **Parsing & sync** — M3U playlists are line-streamed and Xtream `player_api` JSON is read with
  `android.util.JsonReader`, so huge provider payloads are never fully buffered. `SyncManager` does a
  clear-then-insert refresh in ~500-row chunked transactions with Flow progress and cancellation.
- **Storage** — a 19-entity Room schema: profiles & sources (`ProfileSourceCrossRef` for sharing),
  content (categories/channels/movies/series/seasons/episodes), per-profile favorites/history/progress/
  downloads, EPG channels/programmes, and FTS4 search tables. Totals come from indexed `COUNT` queries.
- **Lists** — Paging 3 with a bounded `maxSize` keeps memory flat across 50k+ item lists.
- **EPG** — bulk XMLTV is stream-parsed (gzip-aware) into a rolling now→+48h window and pruned.
- **Player** — two engines behind a small `PlaybackEngine` interface: **libmpv** (movies/series, and any
  live stream ExoPlayer can't open) and **ExoPlayer/Media3** (Live TV — instant HLS). Live "promotes" the
  running preview straight to full-screen (no reload); the shell hoists the active surface across full ↔
  mini-player. Player state is published as `StateFlow`s for the Compose HUD.
- **DI** — Koin modules (`appModule`, `databaseModule`, `dataModule`, `playerModule`).

### Project layout

```
tv.own.owntv/
├── core/        database (Room), network, parser (M3U/Xtream/XMLTV), repository, sync, util
├── player/      libmpv + ExoPlayer engines (PlaybackEngine) + Compose surfaces + HUD + mini-player
├── ui/          theme + reusable components (focus surface, cards, state views, avatars)
├── features/    setup, shell, live, movies, series, search, downloads, epg, profiles, settings
└── di/          Koin modules
```

## 📚 Docs & design (`extras/`)

- 📄 **[Complete Product Brief & Build Plan](extras/OwnTV_Complete_Brief_Plan_With_Logo.docx)** — the full
  as-built design brief, architecture, data model, and all 14 build phases.
- 📺 **[Player design reference](extras/player.html)** — an interactive Material 3 mockup of the player UI.
- 🖼️ `extras/logo.png` — the OwnTV logo.

## 📥 Installing (Fire TV / Android TV)

Grab the signed APK from the [**latest release**](https://github.com/ahXN00/OwnTV/releases/latest) and
sideload it. A fixed link always points at the newest signed build:

```
https://github.com/ahXN00/OwnTV/releases/latest/download/OwnTV.apk
```

- **Fire TV** — install the **Downloader** app (by AFTVnews) from the Amazon Appstore, then enter the
  **Downloader code `4308278`** (or [`aftv.news/4308278`](https://aftv.news/4308278), which always
  points at the latest signed `OwnTV.apk`). Enable *Apps from Unknown Sources* if prompted.
- **Android TV / Google TV** — the **Downloader** app is also on Google Play, so the same code
  **`4308278`** works here too. (If Downloader doesn't show in search, open the Play Store on the TV —
  you can reach it via *Settings → Apps → See all apps → Show system apps → Google Play Store* — and
  install it from there.) Or just sideload the APK with your tool of choice (*Send files to TV*, a USB
  drive, or `adb install OwnTV.apk`).

> Only install the APK from this repository's official Releases (or the `…/releases/latest/download/OwnTV.apk`
> link above). It's the build signed by this project's CI — third-party re-hosts aren't endorsed.

## 🛠️ Building & running

1. Open the project in **Android Studio** (a version matching AGP 9.x) and let Gradle sync.
2. Run the `app` configuration on an **Android TV** emulator or device. The app declares a
   `LEANBACK_LAUNCHER` **and** a regular `LAUNCHER` entry and marks the leanback feature **optional**, so
   it shows in the **TV launcher** on Android TV and as a normal app icon on phones/tablets and non-TV
   boxes too (minimum **Android 8.0 / API 26**).
3. Or from the command line:

```bash
./gradlew assembleDebug
```

On first launch you'll go through onboarding: accept the disclaimer, create a profile, then **add a
source** (M3U or Xtream) — or import a backup. After it imports, browse from the sidebar and open the
**Guide** for the EPG. Everything is managed under **Settings**.

**Tested on:** a real **TCL Google TV**, and the **Android Studio emulator** (both the Android TV and
Google TV system images).

## 🤖 CI & releases

GitHub Actions ([`.github/workflows/android.yml`](.github/workflows/android.yml)) builds the app in the
cloud — no local build needed:

- **Every push / PR** → builds a debug APK and uploads it as a workflow **artifact** named
  `OwnTV-v<version>-<sha>.apk` (download it from the run's *Summary → Artifacts*).
- **Push a `v*` tag** (e.g. `git tag v1.1.0 && git push origin v1.1.0`) → builds a **signed** APK and
  publishes a **GitHub Release** with `OwnTV-v1.1.0.apk` attached. The release notes are taken from
  the newest section of [`CHANGELOG.md`](CHANGELOG.md) (which the in-app updater shows as
  "What's new"), plus GitHub's auto-generated commit list.

**Versioning is automatic**: `versionName`/`versionCode` are derived from the tag (e.g. `v1.1.0` →
`1.1.0` / `10100`) — no need to touch `build.gradle.kts`.

**Signed release builds (optional, recommended for distribution).** Tag builds are debug-signed until you
add a release keystore. Create one and add four repo **Secrets** to get properly signed releases:

```bash
keytool -genkey -v -keystore owntv.keystore -alias owntv -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 owntv.keystore   # copy the output into the KEYSTORE_BASE64 secret
```

Then add repo Secrets (*Settings → Secrets and variables → Actions*): `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Keep the keystore file private — it's never committed.

## 🤝 Contributing

Contributions, bug reports, and ideas are welcome — open an issue or a pull request. Please keep the
project's player-only, bring-your-own-source positioning, and match the existing code style.

## 💛 Support the project

OwnTV is — and will **always be** — completely **free, 100% open-source, and ad-free. Forever.** ❤️ No
paywalls, no "Pro" tier, no catch.

It's a passion project built in my spare time. If it's made your TV a little nicer and you'd like to say
thanks or **buy me a coffee**, a small **PayPal** tip is hugely appreciated — but it's **100% optional**, the
app stays free for everyone no matter what. 🙂

### ☕ [paypal.me/AshiqHasan](https://paypal.me/AshiqHasan)

Scan to donate from your phone:

<a href="https://paypal.me/AshiqHasan"><img src="extras/paypal_qr.jpg" alt="Scan to donate via PayPal" width="170"></a>

Thank you for using OwnTV! 🙏

## ⚖️ Legal

OwnTV is a media **player** only. It ships with no channels, playlists, subscriptions, or content, and
does not endorse or facilitate access to unauthorized streams. Users are solely responsible for the
sources they add and for complying with the laws and rights that apply to them.

## 📄 License

Released under the **MIT License** — see [LICENSE](LICENSE).

---

<sub>OwnTV is an open-source, player-only project, built with the help of AI.</sub>
