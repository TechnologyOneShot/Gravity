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


def chatml(system, user, prefix):
    return (f"<|im_start|>system\n{system}<|im_end|>\n"
            f"<|im_start|>user\n{user}<|im_end|>\n"
            f"<|im_start|>assistant\n{prefix}")


def generate(server, prompt, prefix, seed):
    """Same settings as the app (LocalLlamaProvider + AgentEngine.runDirectWebApp)."""
    n_prompt = len(post(server + "/tokenize", {"content": prompt})["tokens"])
    t0 = time.time()
    r = post(server + "/completion", {
        "prompt": prompt, "n_predict": min(CTX - n_prompt - 64, 7500), "temperature": 0.2, "top_k": 40, "top_p": 0.95,
        "min_p": 0.05, "repeat_penalty": 1.05, "repeat_last_n": 64, "stop": ["</html>"], "seed": seed,
        "samplers": ["penalties", "top_k", "top_p", "min_p", "temperature"], "cache_prompt": False,
    })
    content = r.get("content", "")
    reply = prefix + content
    if r.get("stop_type") == "word" and "</html>" not in content.lower():
        reply += "</html>"  # llama-server leaves the stop string out of the content
    return reply, n_prompt, r.get("tokens_predicted", 0), time.time() - t0


def evaluate(kind, html, path_base):
    """(verdict, detail, errors) where errors is what Gravity's on-device test would report."""
    path = path_base + ".html"
    open(path, "w").write(html)
    scripts = re.findall(r"<script[^>]*>([\s\S]*?)</script>", html, re.I)
    js_path = path_base + ".js"
    open(js_path, "w").write("\n;\n".join(scripts))
    syntax = subprocess.run(["node", "--check", js_path], capture_output=True, text=True)
    if syntax.returncode != 0:
        m = re.search(r"(SyntaxError: .*)", syntax.stderr)
        msg = "Uncaught " + (m.group(1) if m else syntax.stderr.strip().splitlines()[-1])
        return "FAIL", f"JavaScript error: {msg}", [msg]
    tap = smoke("tap", path)
    errors = [re.sub(r"^Uncaught \[(.*)\]$", r"Uncaught \1", e) for e in tap.get("errors", [])]
    use = smoke(kind, path)
    return ("PASS" if use["ok"] else "FAIL"), use["detail"], errors


def main():
    prompts_dir, server, out_dir = sys.argv[1:4]
    runs = int(sys.argv[4]) if len(sys.argv) > 4 else 1
    repair = json.load(open(sys.argv[5])) if len(sys.argv) > 5 else None
    os.makedirs(out_dir, exist_ok=True)
    summary = []
    for name in sorted(os.listdir(prompts_dir)):
        if not name.endswith(".json"):
            continue
        p = json.load(open(os.path.join(prompts_dir, name)))
        prompt = chatml(p["system"], p["user"], p["prefix"])
        for run in range(1, runs + 1):
            tag = f"{p['name']}#{run}"
            reply, n_prompt, predicted, secs = generate(server, prompt, p["prefix"], run)
            print(f"=== {tag}: prompt {n_prompt} tokens, wrote {predicted} tokens in {secs:.0f} s ===")
            open(os.path.join(out_dir, f"{p['name']}-{run}.reply.txt"), "w").write(reply)
            html = extract_html(reply)
            if html is None:
                summary.append((tag, "FAIL", "FAIL", predicted, "no complete page"))
                print(f"RESULT {tag}: FAIL, no complete page. Reply ends with: {reply[-300:]!r}")
                continue
            first, detail, errors = evaluate(p.get("kind", p["name"]), html, os.path.join(out_dir, f"{p['name']}-{run}"))
            print(f"RESULT {tag} first try: {first}: {detail}; test errors: {errors or 'none'}")
            print(html)
            final, final_detail = first, detail
            if errors and repair:
                user = repair["user"].replace("{{HTML}}", html).replace("- {{ERRORS}}", "\n".join(f"- {e}" for e in errors))
                fixed_reply, _, fixed_tokens, fixed_secs = generate(server, chatml(repair["system"], user, repair["prefix"]), repair["prefix"], run)
                fixed = extract_html(fixed_reply)
                if fixed is None:
                    print(f"RESULT {tag} repair: no complete page ({fixed_tokens} tokens); first version kept")
                else:
                    v2, d2, e2 = evaluate(p.get("kind", p["name"]), fixed, os.path.join(out_dir, f"{p['name']}-{run}-fixed"))
                    kept = len(e2) < len(errors)
                    print(f"RESULT {tag} after repair ({fixed_tokens} tokens, {fixed_secs:.0f} s): {v2}: {d2}; test errors: {e2 or 'none'}; "
                          f"{'kept' if kept else 'not better, first version kept'}")
                    print(fixed)
                    if kept:
                        final, final_detail = v2, d2
            summary.append((tag, first, final, predicted, final_detail))
            print()
    print("\n== Summary (first try -> after Gravity's test-and-fix step) ==")
    for tag, first, final, tokens, detail in summary:
        print(f"{first} -> {final}  {tag:16} {tokens:5} tokens  {detail}")
    print(f"WORKING APPS: first try {sum(1 for s in summary if s[1] == 'PASS')}/{len(summary)}, "
          f"after test-and-fix {sum(1 for s in summary if s[2] == 'PASS')}/{len(summary)}")
    sys.exit(0 if summary else 1)


if __name__ == "__main__":
    main()
