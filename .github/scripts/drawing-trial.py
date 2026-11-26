#!/usr/bin/env python3
"""Drawing trial: one system prompt, five picture requests as the app sends them, the app's sampler.
Prints every reply in full between markers. Usage: drawing-trial.py <system file> <questions file> <url> <repeat penalty>"""
import json, sys, time, urllib.request

system = open(sys.argv[1], encoding="utf-8").read()
questions = open(sys.argv[2], encoding="utf-8").read().split("\u0000")
url, penalty = sys.argv[3], float(sys.argv[4])
for i, q in enumerate(questions):
    body = {"messages": [{"role": "system", "content": system}, {"role": "user", "content": q}], "max_tokens": 2500,
            "temperature": 0.4, "top_k": 40, "top_p": 0.95, "min_p": 0.05, "repeat_penalty": penalty, "repeat_last_n": 64,
            "seed": 7, "samplers": ["penalties", "top_k", "top_p", "min_p", "temperature"], "cache_prompt": False}
    req = urllib.request.Request(url + "/v1/chat/completions", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    t = time.time()
    with urllib.request.urlopen(req, timeout=1800) as r:
        data = json.loads(r.read())
    tm = data.get("timings", {})
    text = data["choices"][0]["message"]["content"] or ""
    print(f"=====BEGIN {i} | {q.splitlines()[0]} | {time.time() - t:.0f} s, prompt {tm.get('prompt_n')} tokens, "
          f"wrote {tm.get('predicted_n')} tokens ({tm.get('predicted_per_second', 0):.1f}/s), finish {data['choices'][0].get('finish_reason')}", flush=True)
    print(text, flush=True)
    print(f"=====END {i}", flush=True)
