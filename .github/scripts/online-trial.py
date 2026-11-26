#!/usr/bin/env python3
"""Online picture trial: Gemma writes the picture description (old vs new instruction, as the app
sends them), Pollinations (Flux, the app's service and URL) makes the picture; prints each picture as
a small base64 JPEG between markers. Usage: online-trial.py <llama-server url>"""
import base64, io, json, re, time, urllib.parse, urllib.request
from PIL import Image

PICTURE = re.compile(r"(?im)^\s*[*_]*\s*(picture|image|image\s*prompt|prompt|चित्र|तस्वीर)\s*[*_]*\s*[:：]\s*[*_]*\s*(.+)$")
questions = open("otrial/questions.txt", encoding="utf-8").read().split("\u0000")
last = 0.0

def ask(url, system, q):
    body = {"messages": [{"role": "system", "content": system}, {"role": "user", "content": q}], "max_tokens": 400, "temperature": 0.4,
            "top_k": 40, "top_p": 0.95, "min_p": 0.05, "repeat_penalty": 1.05, "repeat_last_n": 64, "seed": 7,
            "samplers": ["penalties", "top_k", "top_p", "min_p", "temperature"]}
    req = urllib.request.Request(url + "/v1/chat/completions", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        return json.loads(r.read())["choices"][0]["message"]["content"] or ""

def picture(prompt):
    global last
    wait = 46 - (time.time() - last)
    if wait > 0: time.sleep(wait)
    url = "https://image.pollinations.ai/prompt/" + urllib.parse.quote(prompt[:1500], safe="") + "?width=1024&height=1024&model=flux&seed=42"
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Gravity-CI"}), timeout=180) as r:
                data = r.read()
            last = time.time()
            return data
        except urllib.error.HTTPError as e:
            last = time.time()
            if e.code in (402, 429, 500, 502, 503):
                time.sleep(min(int(e.headers.get("Retry-After") or 60), 120))
                continue
            raise
    raise RuntimeError("no picture")

def main():
    import sys
    url = sys.argv[1]
    for variant in ["old", "new"]:
        system = open(f"otrial/{variant}.txt", encoding="utf-8").read()
        for i, q in enumerate(questions):
            reply = ask(url, system, q)
            m = PICTURE.search(reply)
            desc = m.group(2).strip().strip("*_\" ") if m else ""
            print(f"=====PIC {variant} {i} | {q.splitlines()[0]}", flush=True)
            print(f"DESCRIPTION ({len(desc.split())} words): {desc}", flush=True)
            if not desc:
                print("NO PICTURE LINE. Reply: " + reply[:300], flush=True)
                continue
            try:
                img = Image.open(io.BytesIO(picture(desc))).convert("RGB")
                img.thumbnail((320, 320))
                buf = io.BytesIO(); img.save(buf, "JPEG", quality=80)
                print("B64 " + base64.b64encode(buf.getvalue()).decode(), flush=True)
            except Exception as e:
                print(f"PICTURE FAILED: {e}", flush=True)

main()
