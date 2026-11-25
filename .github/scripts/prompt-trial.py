#!/usr/bin/env python3
"""Prompt trial: asks one model every case under every system prompt in a folder and scores
each answer on script (Devanagari vs Latin) and expected words. Usage:
prompt-trial.py <variants dir> <cases.json> <llama-server url> <out dir> <model>"""
import json, pathlib, re, sys, time, urllib.request

def ask(url, messages):
    body = {"messages": messages, "max_tokens": 1024, "temperature": 0.4, "top_k": 40, "top_p": 0.95,
            "min_p": 0.05, "repeat_penalty": 1.05, "repeat_last_n": 64, "seed": 7,
            "samplers": ["penalties", "top_k", "top_p", "min_p", "temperature"], "cache_prompt": False}
    req = urllib.request.Request(url + "/v1/chat/completions", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    t = time.time()
    with urllib.request.urlopen(req, timeout=900) as r:
        data = json.loads(r.read())
    tm = data.get("timings", {})
    return data["choices"][0]["message"]["content"] or "", time.time() - t, tm.get("prompt_n"), tm.get("prompt_ms")

HINDI_WORDS = re.compile(r"\b(hai|hain|nahi|nahin|karo|kar|karein|aap|apne|apna|ke|ki|ko|mein|se|aur|bhi|tum|ye|yeh)\b", re.I)

def score(case, text):
    letters = [c for c in text if c.isalpha()]
    deva = sum(1 for c in letters if 'ऀ' <= c <= 'ॿ')
    ratio = deva / max(1, len(letters))
    problems = []
    script = case.get("script")
    if script == "latin" and ratio > 0.1: problems.append(f"wrong script (Devanagari {ratio:.0%})")
    if script == "devanagari" and ratio < 0.5: problems.append(f"wrong script (Devanagari only {ratio:.0%})")
    if case.get("hinglish") and len(HINDI_WORDS.findall(text)) < 3: problems.append("not Hinglish (English?)")
    exp = case.get("expect_any", [])
    if exp and not any(e.lower() in text.lower() for e in exp): problems.append("missing: " + "/".join(exp))
    return problems

def main():
    vdir, cases_path, url, out, model = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3], pathlib.Path(sys.argv[4]), sys.argv[5]
    plain = json.loads(cases_path.read_text(encoding="utf-8"))
    hinted_path = cases_path.with_name("cases_hint.json")
    hinted = json.loads(hinted_path.read_text(encoding="utf-8")) if hinted_path.exists() else plain
    out.mkdir(parents=True, exist_ok=True)
    summary = []
    for v in sorted(vdir.glob("*.txt")):
        system = v.read_text(encoding="utf-8")
        cases = hinted if "hint" in v.stem else plain
        if "__" in v.stem:
            cases = json.loads(cases_path.with_name("cases_" + v.stem.split("__")[1] + ".json").read_text(encoding="utf-8"))
        report = [f"# {model} / {v.stem}\n"]
        ok_count, prompt_tokens = 0, None
        for case in cases:
            text, took, pn, pms = ask(url, [{"role": "system", "content": system}] + case["messages"])
            if prompt_tokens is None and len(case["messages"]) == 1: prompt_tokens = pn
            # An answer that repeats the question must not pass on the question's own words.
            q = case["messages"][-1]["content"].split("\n\n(")[0].strip()
            problems = score(case, text.replace(q, " ") if len(q) > 12 else text)
            ok_count += not problems
            head = f"## {case['name']}  [{'OK' if not problems else '; '.join(problems)}]  ({took:.0f} s, prompt {pn} tokens)"
            print(f"[{v.stem}] {head}", flush=True)
            print("Q: " + case["messages"][-1]["content"].replace("\n", " / "), flush=True)
            print(text.strip(), flush=True)
            print("-" * 60, flush=True)
            report.append(f"{head}\n\n**Q:** {case['messages'][-1]['content']}\n\n{text.strip()}\n")
        line = f"RESULT {model} {v.stem}: {ok_count}/{len(cases)} OK, system+question ≈ {prompt_tokens} tokens"
        print(line, flush=True)
        summary.append(line)
        (out / f"{v.stem}.md").write_text("\n".join(report), encoding="utf-8")
    (out / "summary.txt").write_text("\n".join(summary) + "\n", encoding="utf-8")
    print("\n".join(summary))

if __name__ == "__main__":
    main()
