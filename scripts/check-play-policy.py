#!/usr/bin/env python3
"""Checks a release APK's manifest against the Google Play policies Aries runs into.

Usage: check-play-policy.py MANIFEST.xml
MANIFEST.xml is the decoded manifest of the built APK:
  apkanalyzer manifest print app-release.apk > manifest.xml

Checked (fails the release build):
  - target SDK: Play's target API level requirement for new apps and updates.
  - notification permission: an FCM receiver needs POST_NOTIFICATIONS (API 33+).
  - foreground service types: an app that starts foreground services declares a
    type on them (API 34+) and holds the matching FOREGROUND_SERVICE_<TYPE>
    permission. Play also needs a foreground service declaration in the Console.
    WorkManager's untyped SystemForegroundService is allowed: it runs expedited
    work as a foreground service only below API 31, where no type is needed,
    and Aries never calls setForeground().
  - restricted permissions that need a Play Console declaration or are
    disallowed for an app like Aries (background network goes through FCM and
    WorkManager, not a battery-optimization exemption).
"""

import sys
import xml.etree.ElementTree as ET

# Play: new apps and updates must target the API level released in the year
# before each Aug 31 deadline. Android 16 (API 36) since Aug 31, 2026.
MIN_TARGET_SDK = 36

A = "{http://schemas.android.com/apk/res/android}"

# android:foregroundServiceType flag bits (ServiceInfo.FOREGROUND_SERVICE_TYPE_*)
# -> the type's manifest name and the permission it needs (None: no permission).
FGS_TYPES = {
    0x1: ("dataSync", "FOREGROUND_SERVICE_DATA_SYNC"),
    0x2: ("mediaPlayback", "FOREGROUND_SERVICE_MEDIA_PLAYBACK"),
    0x4: ("phoneCall", "FOREGROUND_SERVICE_PHONE_CALL"),
    0x8: ("location", "FOREGROUND_SERVICE_LOCATION"),
    0x10: ("connectedDevice", "FOREGROUND_SERVICE_CONNECTED_DEVICE"),
    0x20: ("mediaProjection", "FOREGROUND_SERVICE_MEDIA_PROJECTION"),
    0x40: ("camera", "FOREGROUND_SERVICE_CAMERA"),
    0x80: ("microphone", "FOREGROUND_SERVICE_MICROPHONE"),
    0x100: ("health", "FOREGROUND_SERVICE_HEALTH"),
    0x200: ("remoteMessaging", "FOREGROUND_SERVICE_REMOTE_MESSAGING"),
    0x400: ("systemExempted", "FOREGROUND_SERVICE_SYSTEM_EXEMPTED"),
    0x800: ("shortService", None),
    0x2000: ("mediaProcessing", "FOREGROUND_SERVICE_MEDIA_PROCESSING"),
    0x40000000: ("specialUse", "FOREGROUND_SERVICE_SPECIAL_USE"),
}
FGS_BY_NAME = {name: perm for name, perm in FGS_TYPES.values()}

# Permissions Play restricts. Adding one to the app means a Console
# declaration (and usually a review); remove it from this list on purpose.
RESTRICTED = {
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS": "battery-optimization exemption is allowed only when the core function breaks under Doze",
    "android.permission.QUERY_ALL_PACKAGES": "needs a Play declaration",
    "android.permission.MANAGE_EXTERNAL_STORAGE": "needs a Play declaration",
    "android.permission.REQUEST_INSTALL_PACKAGES": "needs a Play declaration",
    "android.permission.ACCESS_BACKGROUND_LOCATION": "needs a Play declaration",
    "android.permission.USE_EXACT_ALARM": "allowed only for alarm and calendar apps",
    "android.permission.USE_FULL_SCREEN_INTENT": "allowed only for calling and alarm apps",
    "android.permission.SYSTEM_ALERT_WINDOW": "needs a Play declaration",
    "android.permission.READ_SMS": "needs a Play declaration",
    "android.permission.READ_CALL_LOG": "needs a Play declaration",
}

FCM_ACTION = "com.google.firebase.MESSAGING_EVENT"

WORKMANAGER_FGS = "androidx.work.impl.foreground.SystemForegroundService"


def fgs_types(value):
    """Type names in an android:foregroundServiceType value (hex flags or names)."""
    try:
        bits = int(value, 0)
    except ValueError:
        return [v.strip() for v in value.split("|") if v.strip()]
    return [name for bit, (name, _) in FGS_TYPES.items() if bits & bit]


def check(root):
    errors = []
    perms = {e.get(A + "name") for e in root.iter("uses-permission")}

    sdk = root.find("uses-sdk")
    target = sdk.get(A + "targetSdkVersion") if sdk is not None else None
    if target is None or int(target) < MIN_TARGET_SDK:
        errors.append(f"targetSdkVersion {target} is below Play's required {MIN_TARGET_SDK}")

    services = list(root.iter("service"))
    fcm = [s.get(A + "name") for s in services
           if any(a.get(A + "name") == FCM_ACTION for a in s.iter("action"))]
    if fcm and "android.permission.POST_NOTIFICATIONS" not in perms:
        errors.append(f"FCM service {fcm[0]} needs android.permission.POST_NOTIFICATIONS (API 33+)")

    typed = [(s.get(A + "name"), fgs_types(s.get(A + "foregroundServiceType")))
             for s in services if s.get(A + "foregroundServiceType")]
    workmanager = any(s.get(A + "name") == WORKMANAGER_FGS for s in services)
    if "android.permission.FOREGROUND_SERVICE" in perms and not typed and not workmanager:
        errors.append("FOREGROUND_SERVICE is requested but no service declares android:foregroundServiceType (API 34+)")
    if typed and "android.permission.FOREGROUND_SERVICE" not in perms:
        errors.append("a service declares a foreground service type but android.permission.FOREGROUND_SERVICE is not requested")
    for name, types in typed:
        for t in types:
            if t not in FGS_BY_NAME:
                errors.append(f"service {name}: unknown foreground service type {t}")
                continue
            need = FGS_BY_NAME[t]
            if need and f"android.permission.{need}" not in perms:
                errors.append(f"service {name} has type {t} but android.permission.{need} is not requested")

    for p in sorted(perms & RESTRICTED.keys()):
        errors.append(f"restricted permission {p}: {RESTRICTED[p]}")

    return errors, typed


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    root = ET.parse(sys.argv[1]).getroot()
    errors, typed = check(root)
    for e in errors:
        print(f"play-policy: {e}", file=sys.stderr)
    for name, types in typed:
        print(f"play-policy: note: {name} runs as a {'|'.join(types)} foreground service; "
              "declare it in Play Console (App content > Foreground service permissions)")
    if not errors:
        print("play-policy: ok")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
