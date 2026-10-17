#!/usr/bin/env bash
# Atalho da Área de Trabalho: sobe o SearchJob só se ainda não estiver no ar e abre o navegador.
URL="http://localhost:8787"
DIR="$(cd "$(dirname "$0")" && pwd)"
LOG="$DIR/data/servidor.log"

# clique duplo (ou vários cliques): só a primeira execução segue; as outras saem sem abrir outro navegador
exec 9>"${XDG_RUNTIME_DIR:-/tmp}/searchjob-abrir.lock"
flock -n 9 || { echo "Já estou abrindo o SearchJob."; exit 0; }

no_ar() { curl -s -o /dev/null --max-time 2 "$URL/"; }

if no_ar; then
  echo "SearchJob já está no ar."
elif pgrep -f "searchjob.*spring-boot:run|SearchJobApplication" >/dev/null; then
  echo "SearchJob está subindo, aguardando..."
else
  mkdir -p "$DIR/data"
  nohup "$DIR/run.sh" >"$LOG" 2>&1 9>&- &
  echo "Subindo o SearchJob..."
fi

for _ in $(seq 1 90); do
  no_ar && break
  sleep 1
done
no_ar || { echo "SearchJob não subiu; veja $LOG"; exit 1; }
xdg-open "$URL" >/dev/null 2>&1 9>&- &
