#!/usr/bin/env python3
"""Capture the website screenshot set from a running emulator or device.

    python3 tools/brand/capture-web-screens.py <locale> <dark|light> <outdir> [--demo]
    python3 tools/brand/capture-web-screens.py en dark brand-kits/rostam/screens/web --demo

--demo populates Protect Mode from RedShieldDemo (src/debug) instead of live
traffic. USE IT FOR ANYTHING PUBLISHED. A real capture puts whatever the device
actually reached on the screen, and on a Rostam asset a row reading
"Blocked - theguardian.com" is read as censorship by the audience this brand
exists for, however legitimate the analytics endpoint behind it is. The
screenshot travels; the explanation does not. The fixture's hosts are
documentation-style (ads.tracker.example, files.dl-free-apk.icu) and its
per-app map points at packages a stock emulator really has, so the icons and
the attribution split are real where the live capture reads 67% "Not matched
to an app". Only the sample destinations are illustrative: the UI, the layout,
the reason lines, the AS and country columns and the counters are the product.

🚨 --demo NEEDS A DEBUG BUILD. RedShieldDemo lives in src/debug and its
src/release twin returns null everywhere, so a release APK has no sample data
at all and the flag does nothing (deliberate: a runtime flag is not an
exclusion, see CLAUDE.md "DEV TOOLS SHIPPED IN EVERY ANDROID RELEASE"). The
UI is the same code either way; the build type differs in minification, not in
pixels. Install the debug APK first, and reinstall the release when done:

    ./gradlew :app:assembleRostamDirectDebug
    adb install -r app/build/outputs/apk/rostamDirect/debug/app-rostam-direct-arm64-v8a-debug.apk

WEBSITE screenshots, not store screenshots: no caption, no device frame, no
padding. The site puts its own frame and its own words around them, and a
caption burned into a PNG cannot be translated later.

Before a run, set the SYSTEM locale as well as the per-app one, or the
status-bar clock stays in Persian digits on an English shot:

    adb shell "setprop persist.sys.locale en-US; setprop ctl.restart zygote"
    # wait for the framework, THEN:
    adb shell cmd locale set-app-locales com.rostamvpn.android --locales en-US
    adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural

FIRST RUN ON A FRESH INSTALL needs three taps this script deliberately does
not automate, because walking onboarding blind is how a capture run ends up
screenshotting a consent dialog: the onboarding pages, the pre-connect notice,
and Android's own VPN ConfirmDialog (com.android.vpndialogs). Do them by hand
once; the VPN grant persists, and every later run goes straight through. Do
NOT `adb uninstall` between runs for the same reason.

Raw 1080x2400 screencap, no frame, no caption, no padding. Elements are found
by TEXT through uiautomator rather than by fixed coordinates, so the same run
works in English and in Farsi, where the layout is mirrored.
"""
import re
import subprocess
import sys
import time
from pathlib import Path

DEV = "emulator-5554"
PKG = "com.rostamvpn.android"


def sh(*args, **kw):
    return subprocess.run(["adb", "-s", DEV, *args], capture_output=True, text=True, **kw)


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return sh("shell", "cat", "/sdcard/ui.xml").stdout


def find(pattern, tries=12, delay=1.0):
    """Centre of the first node whose text matches `pattern`."""
    rx = re.compile(pattern)
    for _ in range(tries):
        for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump()):
            t = m.group(1)
            if rx.search(t):
                x1, y1, x2, y2 = map(int, m.groups()[1:])
                return (x1 + x2) // 2, (y1 + y2) // 2
        time.sleep(delay)
    return None


def tap(xy):
    sh("shell", "input", "tap", str(xy[0]), str(xy[1]))
    time.sleep(1.2)


def tap_text(pattern, what=""):
    xy = find(pattern)
    if not xy:
        raise SystemExit(f"could not find {what or pattern}")
    tap(xy)
    return xy


def back():
    sh("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.2)


def swipe_up():
    # A fling, not a drag: the blocked list runs to a hundred rows once real
    # browsing has happened, and a polite scroll never reaches Profiles.
    sh("shell", "input", "swipe", "540", "2000", "540", "300", "120")
    time.sleep(0.7)


def scroll_to(pattern, limit=40):
    for _ in range(limit):
        xy = find(pattern, tries=1, delay=0)
        if xy:
            return xy
        swipe_up()
    return find(pattern, tries=2)


def grab():
    return subprocess.run(["adb", "-s", DEV, "exec-out", "screencap", "-p"],
                          capture_output=True).stdout


def settle(tries=8):
    """Screencap until two consecutive frames match, then return the second.

    A plain sleep is not enough: fa/02-protect-dark in the 477 set caught the
    LAUNCH OVERLAY, the guardian mark and wordmark sitting over a dimmed sheet,
    because the capture landed while it was still fading. This catches that and
    every other animation without guessing a duration. The connected home
    screen has a live throughput readout that may never hold still, so it gives
    up after `tries` and captures anyway rather than hanging."""
    prev = grab()
    for _ in range(tries):
        time.sleep(0.7)
        cur = grab()
        if cur == prev and len(cur) > 1000:
            return cur
        prev = cur
    return prev


def shot(path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    png = settle()
    path.write_bytes(png)
    import struct
    w, h = struct.unpack(">II", png[16:24])
    print(f"  {path.name}  {w}x{h}")
    if (w, h) != (1080, 2400):
        raise SystemExit(f"wrong size for {path}: {w}x{h}")


def demo_bar():
    sh("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
    for cmd in (
        ["-e", "command", "enter"],
        ["-e", "command", "clock", "-e", "hhmm", "0941"],
        ["-e", "command", "battery", "-e", "level", "100", "-e", "plugged", "false"],
        ["-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true"],
        ["-e", "command", "network", "-e", "mobile", "hide"],
        ["-e", "command", "notifications", "-e", "visible", "false"],
    ):
        sh("shell", "am", "broadcast", "-a", "com.android.systemui.demo", *cmd)


TEXT = {
    "en": {"traffic": r"^Traffic$", "blocked": r"^Blocked$", "profiles": r"^Profiles$",
           "on": r"^VPN On$", "off": r"^VPN Off$", "byapp": r"^By app$"},
    "fa": {"traffic": r"ترافیک", "blocked": r"مسدود", "profiles": r"پروفایل",
           "on": r"VPN روشن", "off": r"VPN خاموش", "byapp": r"بر اساس برنامه"},
}


def connected():
    return find(r"^VPN On$|VPN روشن", tries=2, delay=1) is not None


def browse():
    """Real traffic from a real page, so the Protect lists are genuine.
    Two mainstream news sites; whatever their ad stack calls is what shows."""
    for url in ("https://www.theguardian.com/international", "https://edition.cnn.com/"):
        sh("shell", "am", "start", "-n", "org.chromium.webview_shell/.WebViewBrowserActivity",
           "-a", "android.intent.action.VIEW", "-d", url)
        time.sleep(22)
    sh("shell", "am", "force-stop", "org.chromium.webview_shell")
    time.sleep(1)


# Sheet segment RedShieldDemo opens on, per shot. Launching straight onto the
# segment beats tapping through it: no coordinates, no locale, no scrolling.
DEMO_OPEN = {"02-protect": "blocked", "03-apps": "traffic", "04-profiles": "profiles"}


def demo_launch(open_on=None):
    sh("shell", "am", "force-stop", PKG)
    args = ["shell", "am", "start", "-n", f"{PKG}/.MainActivity",
            "--ez", "demo_protected", "true"]
    if open_on:
        args += ["--es", "demo_open", open_on]
    sh(*args)
    time.sleep(5)


def capture_demo(out, suffix):
    """01-04 from the fixture, 05 from a plain launch.

    The tunnel is brought up for real first. RedShieldDemo populates Protect
    Mode, NOT the dial: the dial reads the actual VpnService, so a demo launch
    on its own gives "01-home-connected" showing VPN Off. The connection adds
    nothing to what is on screen, because every list the sheet draws comes from
    the fixture while it is active."""
    # force-stop first: a previous run can leave the app resumed on the
    # profiles screen, where connected() finds no dial, the connect tap lands
    # on a list row and the run dies claiming it could not connect.
    sh("shell", "am", "force-stop", PKG)
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(5)
    if not connected():
        tap((540, 792))
        time.sleep(14)
        if not connected():
            raise SystemExit("did not connect (the dial is real even in demo mode)")
    demo_launch()
    shot(out / f"01-home-connected{suffix}.png")
    for name, seg in DEMO_OPEN.items():
        demo_launch(seg)
        shot(out / f"{name}{suffix}.png")
    sh("shell", "am", "force-stop", PKG)
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(4)
    if connected():
        tap((540, 792))
        time.sleep(6)
    shot(out / f"05-home-idle{suffix}.png")


def main(locale, mode, outroot, demo=False):
    t = TEXT[locale]
    suffix = "-light" if mode == "light" else "-dark"
    out = Path(outroot) / locale
    print(f"== {locale} / {mode}")

    sh("shell", "cmd", "uimode", "night", "no" if mode == "light" else "yes")
    time.sleep(2)
    demo_bar()
    if demo:
        capture_demo(out, suffix)
        return
    sh("shell", "am", "force-stop", PKG)
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(5)

    # 1. home, connected -- after real browsing, so the card shows real blocks
    if not connected():
        tap((540, 792))
        time.sleep(12)
        if not connected():
            raise SystemExit("did not connect")
    browse()
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(4)
    shot(out / f"01-home-connected{suffix}.png")

    # 2. Protect Mode sheet, Blocked tab
    tap_text(r"Protect Mode", "the Protect Mode card")
    time.sleep(1.5)
    shot(out / f"02-protect{suffix}.png")

    # 3. Traffic. App attribution resolves only while a connection is OPEN, so
    # the page is started and the sheet opened while it is still loading --
    # otherwise the by-app map reads "Not matched to an app" for nearly
    # everything, which is true but reads as a fault.
    back()
    time.sleep(1)
    sh("shell", "am", "start", "-n", "org.chromium.webview_shell/.WebViewBrowserActivity",
       "-a", "android.intent.action.VIEW", "-d", "https://edition.cnn.com/")
    # Short on purpose. Attribution resolves only while a socket is OPEN, so
    # the sooner the sheet is read the more of the map is named. At 7s the
    # by-app split was 32/67 against us; at 4s most of the page is still in
    # flight. Too short and there is no traffic at all.
    time.sleep(4)
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2.5)
    tap_text(r"Protect Mode", "the Protect Mode card")
    time.sleep(1.5)
    tap_text(t["traffic"], "the Traffic tab")
    time.sleep(2)
    xy = find(t["byapp"], tries=3, delay=0.8)
    if xy:
        tap(xy)
        time.sleep(1.5)
    shot(out / f"03-apps{suffix}.png")

    # 4. Profiles
    tap_text(t["blocked"], "the Blocked tab")
    time.sleep(1.5)
    xy = scroll_to(t["profiles"])
    if not xy:
        raise SystemExit("could not reach Profiles")
    tap(xy)
    time.sleep(2)
    shot(out / f"04-profiles{suffix}.png")

    # 5. home, idle
    back()
    back()
    time.sleep(1.5)
    if connected():
        tap((540, 792))
        time.sleep(6)
    shot(out / f"05-home-idle{suffix}.png")

    # leave it connected for the next pass
    tap((540, 792))
    time.sleep(12)


if __name__ == "__main__":
    pos = [a for a in sys.argv[1:] if not a.startswith("-")]
    main(pos[0], pos[1], pos[2], demo="--demo" in sys.argv[1:])
