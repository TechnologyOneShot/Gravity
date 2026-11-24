#!/usr/bin/env python3
"""Runs Gravity's on-device App Builder prompts through a real small coder model.

Uses llama-server (llama.cpp, same tag as the app) with the app's settings: ChatML prompt,
the reply started with the app's prefix, temperature 0.2, top-k 40, top-p 0.95, min-p 0.05,
repetition penalty 1.05 over 64 tokens, stop at </html>, an 8192-token context. Then applies
the app's acceptance rule (a complete page) and checks that the JavaScript parses.

Usage: phone-model-check.py <prompts dir> <server url> <out dir>
Exit code 1 if any app is not a complete page with valid JavaScript.
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


def main():
    prompts_dir, server, out_dir = sys.argv[1:4]
    os.makedirs(out_dir, exist_ok=True)
    failures = 0
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
        t0 = time.time()
        r = post(server + "/completion", {
            "prompt": prompt, "n_predict": n_predict, "temperature": 0.2, "top_k": 40, "top_p": 0.95,
            "min_p": 0.05, "repeat_penalty": 1.05, "repeat_last_n": 64, "stop": p["stop"],
            "samplers": ["penalties", "top_k", "top_p", "min_p", "temperature"], "cache_prompt": False,
        })
        secs = time.time() - t0
        content = r.get("content", "")
        reply = p["prefix"] + content
        stopped_word = r.get("stop_type") == "word" or bool(r.get("stopping_word")) or r.get("stopped_word") is True
        if stopped_word and "</html>" not in content.lower():
            reply += "</html>"  # llama-server leaves the stop string out of the content
        predicted = r.get("tokens_predicted", 0)
        html = extract_html(reply)
        print(f"=== {p['name']}: prompt {n_prompt} tokens, wrote {predicted} tokens in {secs:.0f} s "
              f"(stop: {r.get('stop_type', '?')}), fits a 4096 context: {n_prompt + predicted < 4096} ===")
        open(os.path.join(out_dir, f"{p['name']}.reply.txt"), "w").write(reply)
        if html is None:
            failures += 1
            print(f"RESULT {p['name']}: FAIL, no complete page. Reply ends with: {reply[-400:]!r}")
            continue
        path = os.path.join(out_dir, f"{p['name']}.html")
        open(path, "w").write(html)
        scripts = re.findall(r"<script[^>]*>([\s\S]*?)</script>", html, re.I)
        js_path = os.path.join(out_dir, f"{p['name']}.js")
        open(js_path, "w").write("\n;\n".join(scripts))
        check = subprocess.run(["node", "--check", js_path], capture_output=True, text=True)
        buttons = len(re.findall(r"<button", html, re.I))
        inputs = len(re.findall(r"<input", html, re.I))
        verdict = "PASS" if check.returncode == 0 and scripts else "FAIL"
        if verdict == "FAIL":
            failures += 1
        print(f"RESULT {p['name']}: {verdict}: page {len(html)} chars, {len(scripts)} script block(s), "
              f"JavaScript {'parses' if check.returncode == 0 else 'has a syntax error: ' + check.stderr.strip()[:300]}, "
              f"{buttons} <button>, {inputs} <input>")
        print(html)
        print()
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
