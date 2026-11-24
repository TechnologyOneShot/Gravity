#!/usr/bin/env python3
"""Runs Gravity's on-device App Builder prompts through a real small coder model.

Uses llama-server (llama.cpp, same tag as the app) with the app's settings: ChatML prompt,
the reply started with the app's prefix, temperature 0.2, top-k 40, top-p 0.95, min-p 0.05,
repetition penalty 1.05 over 64 tokens, stop at </html>, an 8192-token context. Then applies
the app's acceptance rule (a complete page) and checks that the JavaScript parses.

Each app is then used in jsdom (app-smoke-test.js): the calculator must show 15 after
7 + 8 =, the to-do list must list a typed task, the music player must have a file picker,
an audio player and a play button.

Usage: phone-model-check.py <prompts dir> <server url> <out dir> [runs per app]
This is a quality report: it exits 1 only if the model could not be run at all.
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

CTX = 8192


def post(url, body, timeout=1800):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def extract_html(reply):
    """Same rule as AppBuilder.extractHtml (fenced block, else DOCTYPE..</html>)."""
    fenced = None
    for m in re.finditer(r"```[a-zA-Z]*[ \t]*\r?\n([\s\S]*?)```", reply):
        if "<html" in m.group(1).lower() or "<body" in m.group(1).lower():
            fenced = m.group(1)
            break
    if fenced is None:
        low = reply.lower()
        start = low.find("<!doctype")
        if start < 0:
            start = low.find("<html")
        end = low.rfind("</html>")
        if start < 0 or end < start:
            return None
        fenced = reply[start:end + len("</html>")]
    html = fenced.strip()
    low = html.lower()
    ok = ("<html" in low or "<body" in low) and ("</html>" in low or "</body>" in low) and len(html) >= 200
    return html if ok else None


def smoke(kind, path):
    here = os.path.dirname(os.path.abspath(__file__))
    r = subprocess.run(["node", os.path.join(here, "app-smoke-test.js"), kind, path], capture_output=True, text=True, timeout=120)
    try:
        return json.loads(r.stdout.strip().splitlines()[-1])
    except Exception:
        return {"ok": False, "detail": f"smoke test did not run: {r.stderr.strip()[:300]}", "errors": []}


def main():
    prompts_dir, server, out_dir = sys.argv[1:4]
    runs = int(sys.argv[4]) if len(sys.argv) > 4 else 1
    os.makedirs(out_dir, exist_ok=True)
    summary = []
    for name in sorted(os.listdir(prompts_dir)):
        if not name.endswith(".json"):
            continue
        p = json.load(open(os.path.join(prompts_dir, name)))
        prompt = (
            f"<|im_start|>system\n{p['system']}<|im_end|>\n"
            f"<|im_start|>user\n{p['user']}<|im_end|>\n"
            f"<|im_start|>assistant\n{p['prefix']}"
        )
        n_prompt = len(post(server + "/tokenize", {"content": prompt})["tokens"])
        n_predict = min(CTX - n_prompt - 64, 7500)
        for run in range(1, runs + 1):
            tag = f"{p['name']}#{run}"
            t0 = time.time()
            r = post(server + "/completion", {
                "prompt": prompt, "n_predict": n_predict, "temperature": 0.2, "top_k": 40, "top_p": 0.95,
                "min_p": 0.05, "repeat_penalty": 1.05, "repeat_last_n": 64, "stop": p["stop"], "seed": run,
                "samplers": ["penalties", "top_k", "top_p", "min_p", "temperature"], "cache_prompt": False,
            })
            secs = time.time() - t0
            content = r.get("content", "")
            reply = p["prefix"] + content
            if r.get("stop_type") == "word" and "</html>" not in content.lower():
                reply += "</html>"  # llama-server leaves the stop string out of the content
            predicted = r.get("tokens_predicted", 0)
            print(f"=== {tag}: prompt {n_prompt} tokens, wrote {predicted} tokens in {secs:.0f} s (stop: {r.get('stop_type', '?')}) ===")
            open(os.path.join(out_dir, f"{p['name']}-{run}.reply.txt"), "w").write(reply)
            html = extract_html(reply)
            if html is None:
                summary.append((tag, "FAIL", predicted, "no complete page"))
                print(f"RESULT {tag}: FAIL, no complete page. Reply ends with: {reply[-300:]!r}")
                continue
            path = os.path.join(out_dir, f"{p['name']}-{run}.html")
            open(path, "w").write(html)
            scripts = re.findall(r"<script[^>]*>([\s\S]*?)</script>", html, re.I)
            js_path = os.path.join(out_dir, f"{p['name']}-{run}.js")
            open(js_path, "w").write("\n;\n".join(scripts))
            syntax = subprocess.run(["node", "--check", js_path], capture_output=True, text=True)
            if syntax.returncode != 0:
                summary.append((tag, "FAIL", predicted, "JavaScript syntax error"))
                print(f"RESULT {tag}: FAIL, JavaScript syntax error: {syntax.stderr.strip()[:300]}")
            else:
                use = smoke(p["name"], path)
                verdict = "PASS" if use["ok"] else "FAIL"
                summary.append((tag, verdict, predicted, use["detail"]))
                print(f"RESULT {tag}: {verdict}: {use['detail']}" + (f" (errors: {use['errors']})" if use.get("errors") else ""))
            print(html)
            print()
    print("\n== Summary ==")
    for tag, verdict, tokens, detail in summary:
        print(f"{verdict}  {tag:16} {tokens:5} tokens  {detail}")
    passed = sum(1 for s in summary if s[1] == "PASS")
    print(f"WORKING APPS: {passed}/{len(summary)}")
    sys.exit(0 if summary else 1)


if __name__ == "__main__":
    main()
