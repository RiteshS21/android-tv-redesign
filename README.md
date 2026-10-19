# tv-home

**Make a slow, ad-filled Android TV fast and clean again.** Debloat it over Wi-Fi and give it a
tiny Apple TV-style home screen with rotating nature wallpapers. You can hand the whole thing to
Claude Code with one prompt, or do it by hand with the scripts here.

![Before: Google TV home with a sponsored hero. After: clean dock over a Lake Louise wallpaper](before-after.png)

On a 2 GB TCL Google TV this disabled 25 bloat apps and cut the home screen's memory use from
192 MB to 83 MB. Everything is reversible: packages are disabled, never uninstalled.

## Do it with Claude Code (easiest)

1. On the TV: Settings → System → About → press OK on **Build** 7 times → back → Developer options
   → turn on **USB debugging**. Keep the TV on, on the same Wi-Fi as your computer.
2. Clone this repo, open Claude Code inside it (so it can reuse the launcher and scripts), and paste:

   ```text
   My Android TV is slow and full of ads. Find it on my Wi-Fi, connect over ADB, and debloat it:
   disable the manufacturer's ads, telemetry and junk (reversibly, never uninstall), asking me first
   about apps and features I might use. Then build and install a fast, minimal Apple TV-style home
   screen with a dock of my favourite apps and rotating nature wallpapers. Back up before changing
   anything, check every step with screenshots from the TV, and give me a restore script.

   Heads-up from people who've done this: use the adb-shell Python package if macOS blocks adb; on
   Google TV also disable com.google.android.tungsten.setupwraith or it hijacks the Home button; and
   test the Home button from inside an app.
   ```

3. Accept "Allow USB debugging?" on the TV when asked, then answer a few questions: which apps you
   use, which go in the dock, whether you want wallpapers.

The prompt is short on purpose; Claude works out the details. The gotchas below are there in case it gets stuck.

## Do it by hand (macOS)

```bash
./setup.sh                                                  # Python ADB client, JDK 17, Android SDK
tools/find-tv.sh                                            # finds the TV, saves its IP
tools/venv/bin/python -I tools/tv.py --probe                # model, Android version
tools/disable.sh examples/tcl-google-tv-g07/debloat.txt     # or your own list, see examples/
launcher/build-and-install.sh                               # build + install the launcher
```

Undo everything with `tools/restore.sh <the same list>`.

## Do it by hand (Windows)

Needs Python 3 and PowerShell 7 (`pwsh`). JDK 17 and the Android SDK (platform 34, build-tools 34.0.0)
are looked for in `%USERPROFILE%\tvhome-toolchain` (`jdk-17*` and `sdk\`), or set `JAVA_HOME` / `ANDROID_HOME`.

```powershell
python -m venv tools\venv; tools\venv\Scripts\pip install -r tools\requirements.txt
"192.168.1.5" | Out-File -Encoding ascii -NoNewline tools\.tv_host   # your TV's IP
tools\venv\Scripts\python -I tools\tv.py --probe
launcher\build-and-install.ps1 -Overlay examples\vu-glo-google-tv\res   # -Overlay: your TV's config
```

Undo everything with `tools\restore.ps1 <debloat list>` (add `-RemoveLauncher` to uninstall it too).

## What's here

| Path | What it is |
|---|---|
| [PROMPT.md](PROMPT.md) | The prompt for Claude Code / any coding agent |
| [launcher/](launcher/) | The home screen and its screensaver. 4.7 MB including the wallpapers; plain Android, no network |
| [launcher/res/values/config.xml](launcher/res/values/config.xml) | Default dock apps, row order, hidden apps, Live TV app, grid columns |
| [examples/*/res/](examples/vu-glo-google-tv/res/values/config.xml) | Per-TV overrides of that config, applied with `-Overlay` / `OVERLAY=` |
| [tools/tv.py](tools/tv.py) | Pure-Python ADB client: shell, batch, screenshots, chunked APK push |
| [tools/find-tv.sh](tools/find-tv.sh) | Finds Android TVs on your Wi-Fi |
| [tools/type.sh](tools/type.sh) | Type into the TV's on-screen keyboard from your laptop (`tools/type.sh` alone prompts hidden, for passwords) |
| [tools/disable.sh](tools/disable.sh), [tools/restore.sh](tools/restore.sh) | Apply / undo a debloat list |
| [examples/](examples/) | Debloat lists per TV model |

## The launcher

Laid out like tvOS: the photo fills the top half, the dock sits in a rounded glass tray, the rest of
your apps follow in rows of wide icons over a frosted blur. Focus lifts an icon with a light sweep.

- **Top-left**: greeting, clock and date. **Top-right**: a glass capsule with **Search** (Google
  Assistant), **Inputs** (HDMI/AV/Live TV), **Wi-Fi** and **Settings**.
- **Long-press OK on an app**: Move (arrow keys, OK to finish; drag in or out of the dock), Add to /
  Remove from Dock, App info, Hide, Lock with PIN, Uninstall. The layout is remembered on the TV.
- **Recently used** row (needs usage access, granted at install) and **Home Settings** at the bottom:
  clock on/off, recent row, auto-switch to an HDMI input when its device turns on, wallpaper interval
  and source (built-in and/or your photos in `Pictures/Wallpapers`, e.g. sent with LocalSend),
  start the TV on an input, app-lock PIN, shelf darkness, hidden apps, reset order.
- **Free up memory**: force-stops the apps you used in the last day, Greenify-style, by pressing Force
  stop on each app's App info page. Switch on *Home: Free up memory* once in Settings › Accessibility.
  The service is limited to the Settings app (it never sees other apps' screens), only acts during a
  clean-up you start, and the launcher has no internet permission.
- **Aerial screensaver**: the photos drift slowly with a large clock; set as the TV's screensaver at install.
- 12 nature photos (`launcher/assets/wallpapers/`, credits in `launcher/WALLPAPER-CREDITS.md`).

The default dock comes from `config.xml`; once you rearrange apps on the TV, that layout wins
(Home Settings › Reset app order goes back). App lock only applies to apps opened from this launcher.

## Gotchas we hit

- **Hisense-made sets (Vu, some Toshiba) ignore `killBackgroundProcesses`** for third-party callers
  ("Invalid packageName"), hence the accessibility-based Free up memory instead.
- **The remote's Menu key opens the TV's own quick settings** on Vu/Hisense; use long-press OK for app options.

- **macOS blocks the `adb` binary** on the local network ("No route to host"). `tools/tv.py` uses
  the `adb-shell` Python package instead.
- **Google TV hijacks the Home button.** With the stock launcher disabled, the setup app
  (`com.google.android.tungsten.setupwraith`) has a higher HOME priority than any third-party
  launcher. The install script disables it; `restore.sh` re-enables it.
- **Home opened the launcher behind the current app**, because of a stale home task from switching
  launchers. Force-stopping the launcher and `com.android.tv.settings` and pressing Home fixes it.
  The install script does this.
- **Chained `pm` commands** (`pm disable a; pm disable b`) can silently stop after the first. The
  scripts run one per shell call.
- **Apps installed from the Play Store didn't appear**, because the package broadcast was missed
  while another app was in front. The launcher re-checks with `getChangedPackages` on every return.
- **Unsplash blocks scripted downloads** with a bot check. The wallpapers come from Wikimedia
  Commons Featured Pictures instead.

## Supported TVs

Android TV and Google TV: TCL, Xiaomi/Redmi, Hisense, Sony, OnePlus, Realme, Nvidia Shield,
Chromecast with Google TV and most others. Not Samsung (Tizen), LG (webOS) or Fire TV.
Tested on: TCL Google TV (Android 11). Add yours to `examples/`.

## Credits

Inspired by [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). Wallpaper photos are
from Wikimedia Commons under their own licences, see
[launcher/WALLPAPER-CREDITS.md](launcher/WALLPAPER-CREDITS.md). Code is MIT, see [LICENSE](LICENSE).
