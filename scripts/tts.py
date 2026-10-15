#!/usr/bin/env python3
"""Turn episode scripts (episodes/YYYY-MM-DD.txt) into MP3s with ElevenLabs and rebuild docs/episodes.json.

Episode file format:
    title: <titel>
    summary: <één zin>
    ---
    <de uitgeschreven tekst die wordt voorgelezen>

If ElevenLabs fails (no key, credits op, ...), the episode is still published without audio;
the app then reads the text aloud with the phone's own voice.
"""
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
EPISODES = ROOT / "episodes"
DOCS = ROOT / "docs"
AUDIO = DOCS / "audio"

API_KEY = os.environ.get("ELEVENLABS_API_KEY", "").strip()
VOICE_ID = os.environ.get("ELEVENLABS_VOICE_ID", "").strip()
MODEL_ID = os.environ.get("ELEVENLABS_MODEL_ID", "").strip() or "eleven_flash_v2_5"  # cheapest: 0.5 credit/char
BITRATE = 64_000
MAX_EPISODES = 30  # keep the feed (and the repo) small


def parse(path: pathlib.Path) -> dict:
    raw = path.read_text(encoding="utf-8")
    head, sep, body = raw.partition("\n---\n")
    if not sep:
        head, body = "", raw
    meta = {}
    for line in head.splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            meta[k.strip().lower()] = v.strip()
    return {
        "date": path.stem,
        "title": meta.get("title") or f"Midterms update {path.stem}",
        "summary": meta.get("summary", ""),
        "text": body.strip(),
    }


def pick_voice() -> str:
    """Use the configured voice, otherwise the first premade voice on the account."""
    if VOICE_ID:
        return VOICE_ID
    req = urllib.request.Request("https://api.elevenlabs.io/v1/voices", headers={"xi-api-key": API_KEY})
    with urllib.request.urlopen(req, timeout=60) as r:
        voices = json.load(r).get("voices", [])
    premade = [v for v in voices if v.get("category") == "premade"] or voices
    if not premade:
        raise RuntimeError("geen stemmen gevonden op dit ElevenLabs-account")
    print(f"Stem gekozen: {premade[0].get('name')} ({premade[0]['voice_id']})")
    return premade[0]["voice_id"]


def synthesize(text: str, out: pathlib.Path) -> None:
    voice = pick_voice()
    url = f"https://api.elevenlabs.io/v1/text-to-speech/{voice}?output_format=mp3_44100_64"
    payload = {
        "text": text,
        "model_id": MODEL_ID,
        "language_code": "nl",
        "voice_settings": {"stability": 0.5, "similarity_boost": 0.75},
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"xi-api-key": API_KEY, "Content-Type": "application/json", "Accept": "audio/mpeg"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            out.write_bytes(r.read())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"ElevenLabs gaf {e.code}: {e.read().decode(errors='replace')[:500]}") from None


def main() -> int:
    AUDIO.mkdir(parents=True, exist_ok=True)
    files = sorted(EPISODES.glob("????-??-??.txt"), reverse=True)[:MAX_EPISODES]
    feed = []
    for f in files:
        ep = parse(f)
        mp3 = AUDIO / f"{ep['date']}.mp3"
        if not mp3.exists() and ep["text"]:
            if not API_KEY:
                print(f"::warning::Geen ELEVENLABS_API_KEY; {ep['date']} wordt alleen als tekst gepubliceerd")
            else:
                try:
                    print(f"Audio maken voor {ep['date']} ({len(ep['text'])} tekens, model {MODEL_ID})")
                    synthesize(ep["text"], mp3)
                except Exception as e:  # publish text-only rather than nothing
                    print(f"::warning::Audio voor {ep['date']} mislukt: {e}")
                    mp3.unlink(missing_ok=True)
        if mp3.exists():
            ep["audio"] = f"audio/{mp3.name}"
            ep["duration"] = round(mp3.stat().st_size * 8 / BITRATE)
        else:
            ep["audio"] = None
            ep["duration"] = round(len(ep["text"].split()) / 2.5)  # ~150 words per minute
        feed.append(ep)

    # Drop audio of episodes that fell out of the feed.
    keep = {f"{e['date']}.mp3" for e in feed}
    for old in AUDIO.glob("*.mp3"):
        if old.name not in keep:
            old.unlink()

    (DOCS / "episodes.json").write_text(
        json.dumps({"podcast": "Midterms Dagelijks", "language": "nl", "episodes": feed},
                   ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"Feed bijgewerkt: {len(feed)} afleveringen")
    return 0


if __name__ == "__main__":
    sys.exit(main())
