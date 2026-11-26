"""Speed of offline voices (sherpa-onnx 1.13.6) on this machine: seconds to make each line, real-time factor, first line latency."""
import sys, time, json, soundfile as sf, sherpa_onnx
V = sys.argv[1]; threads = int(sys.argv[2])
lines = {
    "hi": ["अरे, ये तो बहुत अच्छा है!", "मैं आपके लिए एक गाना बना सकती हूँ, और फिर हम उसे सुन सकते हैं।", "आप क्या करना चाहते हैं? मुझे बताइए।"],
    "en": ["Oh, that is really nice!", "I can make a song for you, and then we can listen to it together.", "What would you like to do next? Just tell me."],
}
def piper(name):
    d = f"{V}/vits-piper-{name}"
    onnx = name + ".onnx"
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/{onnx}", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data"), num_threads=threads)))
def kokoro(lang):
    d = f"{V}/kokoro-int8-multi-lang-v1_0"
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(model=f"{d}/model.int8.onnx", voices=f"{d}/voices.bin", tokens=f"{d}/tokens.txt",
            data_dir=f"{d}/espeak-ng-data", lexicon=f"{d}/lexicon-us-en.txt" if lang == "en" else "", lang="" if lang == "en" else "hi"),
        num_threads=threads)))
voices = [("piper hi_IN-priyamvada-medium", lambda: piper("hi_IN-priyamvada-medium"), 0, "hi"),
          ("piper en_US-lessac-medium", lambda: piper("en_US-lessac-medium"), 0, "en"),
          ("piper en_US-hfc_female-medium", lambda: piper("en_US-hfc_female-medium"), 0, "en"),
          ("kokoro hf_alpha (Hindi)", lambda: kokoro("hi"), 31, "hi"),
          ("kokoro af_heart (English)", lambda: kokoro("en"), 3, "en")]
for label, make, sid, lang in voices:
    t0 = time.time(); tts = make(); load = time.time() - t0
    made = audio = 0.0; first = None
    for i, t in enumerate(lines[lang]):
        t0 = time.time(); a = tts.generate(t, sid=sid, speed=1.0); dt = time.time() - t0
        if first is None: first = dt
        made += dt; audio += len(a.samples) / a.sample_rate
        sf.write(f"/tmp/samples/{label.split()[1]}-{i}.wav", a.samples, a.sample_rate)
    print(json.dumps({"voice": label, "threads": threads, "load_s": round(load, 2), "first_line_s": round(first, 2), "rtf": round(made / audio, 3), "audio_s": round(audio, 1)}), flush=True)
