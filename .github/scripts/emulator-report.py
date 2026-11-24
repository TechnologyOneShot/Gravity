"""Prints the on-device test results so they can be read straight from the CI log."""
import glob
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

out = pathlib.Path("ci-out")
print("== Device ==")
print((out / "device.txt").read_text(errors="replace").strip() if (out / "device.txt").exists() else "unknown")

print("\n== What the app did (GravityCI log) ==")
log = out / "gravity-ci.txt"
print(log.read_text(errors="replace") if log.exists() else "(no GravityCI log captured)")

print("== Instrumented test results ==")
total = failed = skipped = 0
for f in sorted(glob.glob(str(out / "androidTest-results/**/*.xml"), recursive=True)):
    root = ET.parse(f).getroot()
    for case in root.iter("testcase"):
        total += 1
        fail = case.find("failure") if case.find("failure") is not None else case.find("error")
        if case.find("skipped") is not None:
            skipped += 1
            print(f"SKIP {case.get('classname')}.{case.get('name')}")
        elif fail is not None:
            failed += 1
            print(f"FAIL {case.get('classname')}.{case.get('name')}")
            print("     " + (fail.text or fail.get("message") or "").strip()[:2500].replace("\n", "\n     "))
        else:
            print(f"ok   {case.get('classname')}.{case.get('name')} ({case.get('time')}s)")
print(f"INSTRUMENTED TESTS: {total} total, {failed} failed, {skipped} skipped")

full = out / "logcat-full.txt"
if full.exists():
    lines = full.read_text(errors="replace").splitlines()
    timeline = [l for l in lines if " TestRunner: " in l and ("started:" in l or "finished:" in l or "failed:" in l)]
    print("\n== Test runner timeline ==")
    print("\n".join(timeline[-80:]))
    mains = [i for i, l in enumerate(lines) if "THREAD_STATE:" in l and "Thread[main," in l]
    for i in mains[:2]:
        print("\n== Main thread state (test runner dump) ==")
        print("\n".join(lines[i:i + 45]))
    all_crashes = [i for i, l in enumerate(lines) if "AndroidRuntime: FATAL EXCEPTION" in l]

    def crash_process(i):
        for l in lines[i:i + 3]:
            if "Process: " in l:
                return l.split("Process: ", 1)[1].split(",")[0].strip()
        return "?"

    # Crashes of Gravity and the apps it generates count; other emulator apps (e.g. Gmail) are listed only.
    crashes = [i for i in all_crashes if crash_process(i).startswith("com.gravity")]
    others = [f"{crash_process(i)}: {lines[i + 2].split('AndroidRuntime: ', 1)[-1] if i + 2 < len(lines) else ''}" for i in all_crashes if i not in crashes]
    if others:
        print(f"\n== Crashes of other apps on the emulator (not Gravity): {len(others)} ==")
        print("\n".join(others[:10]))
    for i in crashes[:2]:
        print("\n== Log lines before crash (warnings/errors) ==")
        before = [l for l in lines[max(0, i - 400):i] if (" E " in l or " W " in l) and "chatty" not in l]
        print("\n".join(before[-40:]))
    print(f"\n== FATAL EXCEPTION blocks in logcat (Gravity and its generated apps): {len(crashes)} ==")
    for i in crashes[:5]:
        print("\n".join(lines[i:i + 40]))
        print("-----")

shots = sorted(p.name for p in (out / "screenshots").rglob("*.png")) if (out / "screenshots").exists() else []
print(f"\n== Screenshots captured: {len(shots)} ==")
print("\n".join(shots))

code = int((out / "exit-code").read_text().strip() or "1") if (out / "exit-code").exists() else 1
if total == 0:
    print("\nNo instrumented test results were produced; gradle output tail:")
    g = out / "gradle-connected.txt"
    if g.exists():
        print("\n".join(g.read_text(errors="replace").splitlines()[-120:]))
sys.exit(1 if code != 0 or failed or total == 0 else 0)
