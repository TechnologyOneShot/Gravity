#!/usr/bin/env python3
"""Ask a phone-sized model the chat questions exported by ChatPromptTest, the way the app does.

Usage: phone-model-chat-check.py <chat-prompts dir> <llama-server url> <out dir> <model name>

Uses the app's system prompt (core/ChatPrompt.kt) and the app's sampler settings for chat:
temperature 0.4, top-k 40, top-p 0.95, min-p 0.05, repeat penalty 1.05 over the last 64 tokens
(same order as gravity_core.cpp). The chat template is the model's own (llama-server without
--jinja uses llama_chat_apply_template, as the app does).

A report, not a pass/fail quality judge: it writes every answer in full so a person can read the
Hindi, plus simple automatic checks (e.g. the capability answer also lists what it cannot do).
Only a run that cannot execute fails.
"""
import json
import pathlib
import sys
import time
import urllib.request


def ask(url, messages):
    body = {
        "messages": messages, "max_tokens": 1024, "temperature": 0.4, "top_k": 40, "top_p": 0.95,
        "min_p": 0.05, "repeat_penalty": 1.05, "repeat_last_n": 64, "seed": 7,
        "samplers": ["penalties", "top_k", "top_p", "min_p", "temperature"], "cache_prompt": False,
    }
    req = urllib.request.Request(url + "/v1/chat/completions", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    start = time.time()
    with urllib.request.urlopen(req, timeout=900) as r:
        data = json.loads(r.read())
    took = time.time() - start
    text = data["choices"][0]["message"]["content"] or ""
    timings = data.get("timings", {})
    return text, took, timings.get("predicted_per_second"), timings.get("predicted_n")


def main():
    src, url, out, model = pathlib.Path(sys.argv[1]), sys.argv[2], pathlib.Path(sys.argv[3]), sys.argv[4]
    system = (src / "system.txt").read_text(encoding="utf-8")
    cases = json.loads((src / "cases.json").read_text(encoding="utf-8"))
    out.mkdir(parents=True, exist_ok=True)
    report = [f"# {model}: chat answers (app prompt and sampler)\n"]
    misses = 0
    for case in cases:
        messages = [{"role": "system", "content": system}] + case["messages"]
        text, took, tps, n = ask(url, messages)
        expect = case.get("expect_any", [])
        ok = (not expect) or any(e.lower() in text.lower() for e in expect)
        if not ok:
            misses += 1
        speed = f"{tps:.1f} tokens/s on the runner" if tps else "speed not reported"
        head = f"## {case['name']}  [{'OK' if ok else 'MISSING: ' + ' / '.join(expect)}]  ({took:.0f} s, {n} tokens, {speed})"
        question = case["messages"][-1]["content"]
        report.append(f"{head}\n\n**Question:** {question}\n\n**Answer:**\n\n{text.strip()}\n")
        print(head, flush=True)
        print("Q:", question, flush=True)
        print(text.strip(), flush=True)
        print("-" * 70, flush=True)
    report.append(f"\nAutomatic checks missed: {misses} of {len(cases)}\n")
    (out / "answers.md").write_text("\n".join(report), encoding="utf-8")
    print(f"SUMMARY {model}: automatic checks missed {misses} of {len(cases)}")


if __name__ == "__main__":
    main()
