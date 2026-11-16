#!/bin/sh
# 使い方: ./run.sh                         → GUI
#         ./run.sh <プロジェクトディレクトリ> [出力先]
DIR=$(cd "$(dirname "$0")" && pwd)
if [ $# -eq 0 ]; then
  exec java -jar "$DIR/dist/jakartaee-analyzer.jar" --gui
fi
OUT=${2:-analysis-output}
exec java -jar "$DIR/dist/jakartaee-analyzer.jar" "$1" -o "$OUT"
