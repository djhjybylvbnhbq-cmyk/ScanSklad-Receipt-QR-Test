#!/usr/bin/env bash
set -euo pipefail
DEST="app/src/main/assets/tessdata"
mkdir -p "$DEST"
for lang in rus eng; do
  file="$DEST/${lang}.traineddata"
  echo "Download official tessdata_fast: $lang"
  curl -fL --retry 5 --retry-delay 3 --connect-timeout 20 \
    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/${lang}.traineddata" \
    -o "$file"
  if [[ ! -s "$file" ]]; then echo "ERROR: empty $file" >&2; exit 1; fi
  echo "$(basename "$file") size: $(wc -c < "$file") bytes"
done
