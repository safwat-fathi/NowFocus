#!/bin/bash
# Renders src/script.json to public/voiceover/<id>.wav with Kokoro-82M (local, via mlx-audio).
# One-time setup: uv venv ~/.cache/kokoro-venv --python 3.13 && uv pip install --python ~/.cache/kokoro-venv/bin/python mlx-audio soundfile "misaki[en]"
set -euo pipefail
cd "$(dirname "$0")/.."
export VIRTUAL_ENV="$HOME/.cache/kokoro-venv" PATH="$HOME/.cache/kokoro-venv/bin:$PATH"
VOICE="${VOICE:-af_heart}"
SPEED="${SPEED:-1.0}"
mkdir -p public/voiceover
node -e 'for (const s of require("./src/script.json")) console.log(s.id + "\t" + s.text)' |
while IFS=$'\t' read -r id text; do
  rm -f "public/voiceover/$id"*.wav
  python -m mlx_audio.tts.generate --model prince-canuma/Kokoro-82M --text "$text" \
    --voice "$VOICE" --speed "$SPEED" --lang_code a --join_audio \
    --output_path public/voiceover --file_prefix "$id" --audio_format wav >/dev/null 2>&1
  [ -f "public/voiceover/$id.wav" ] || mv "public/voiceover/${id}_000.wav" "public/voiceover/$id.wav"
  echo "$id: $(afinfo "public/voiceover/$id.wav" | awk '/estimated duration/{print $3}')s"
done
