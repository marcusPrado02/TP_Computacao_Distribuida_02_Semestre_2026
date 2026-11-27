#!/usr/bin/env bash
# Análise de desempenho (Entrega 2): cluster com 1..N nós x execução sequencial numa máquina.
#
# Para cada N em $NODES_LIST:
#   - sobe N processos Java (portas locais), espera o líder terminar e lê o tempo das rodadas
#     ("LÍDER: R rodada(s) em T ms") e a melhor rota ("FIM: melhor rota = L");
#   - roda a linha de base sequencial (SequentialBaseline) com o MESMO trabalho;
#   - repete $REPS vezes e fica com a mediana.
#
# Dois modos:
#   MODE=weak   (padrão) cada nó roda ANTS formigas: o trabalho total cresce com N. A linha de base
#               faz o mesmo trabalho (N grupos de formigas + N depósitos por rodada) numa máquina.
#               speedup = T_sequencial(N) / T_cluster(N)
#   MODE=strong total fixo de ANTS_TOTAL formigas por rodada, dividido entre os N nós. A linha de base
#               é 1 nó com ANTS_TOTAL formigas. speedup = T_sequencial / T_cluster(N)
#
# Para imitar N máquinas de T núcleos numa máquina só, cada nó usa ANT_THREADS=T threads e a linha de
# base também usa T (uma "máquina"). Precisa de N*T <= núcleos; senão os nós disputam CPU e o
# resultado mede contenção, não distribuição. Em máquinas físicas, use ANT_THREADS=0 (todos os núcleos).
#
# Uso:  scripts/bench.sh                 (precisa de target/aco-node.jar: mvn -q -DskipTests package)
#       MODE=strong REPS=3 scripts/bench.sh
#       NODES_LIST="1 2 4" ROUNDS=20 ITERS=100 scripts/bench.sh
set -euo pipefail

cd "$(dirname "$0")/.."
JAR=${JAR:-target/aco-node.jar}
[[ -f $JAR ]] || { echo "Falta $JAR: rode 'mvn -q -DskipTests package'" >&2; exit 1; }

MODE=${MODE:-weak}
NODES_LIST=${NODES_LIST:-"1 2 3 4"}
REPS=${REPS:-3}
ROUNDS=${ROUNDS:-30}
ANTS=${ANTS:-40}
ANTS_TOTAL=${ANTS_TOTAL:-160}
ITERS=${ITERS:-50}
THREADS=${ANT_THREADS:-4}
TSP_FILE=${TSP_FILE:-data/berlin52.tsp}
BASE_PORT=${BASE_PORT:-56100}
OUT=${OUT:-docs/desempenho}
TIMEOUT_S=${TIMEOUT_S:-600}
TOKEN=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
LOGS=$(mktemp -d)
mkdir -p "$OUT"
CSV="$OUT/resultados-$MODE.csv"

cores=$(nproc 2>/dev/null || echo 1)
max_n=$(echo "$NODES_LIST" | tr ' ' '\n' | sort -n | tail -1)
if (( THREADS > 0 && max_n * THREADS > cores )); then
  echo "AVISO: $max_n nós x $THREADS threads > $cores núcleos; os nós vão disputar CPU." >&2
fi

pids=()
cleanup() { for p in "${pids[@]:-}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; }
trap 'cleanup; rm -rf "$LOGS"' EXIT

median() { sort -n | awk '{a[NR]=$1} END {print (NR%2 ? a[(NR+1)/2] : int((a[NR/2]+a[NR/2+1])/2))}'; }

ants_for() { if [[ $MODE == strong ]]; then echo $(( ANTS_TOTAL / $1 )); else echo "$ANTS"; fi; }

# Sobe N nós, espera o FIM do líder (maior id) e imprime "tempo_ms melhor".
run_cluster() {
  local n=$1 ants=$2 rep=$3
  pids=()
  for id in $(seq 1 "$n"); do
    local peers=""
    for other in $(seq 1 "$n"); do
      [[ $other == "$id" ]] && continue
      peers+="${peers:+,}$other=localhost:$((BASE_PORT + other))"
    done
    NODE_ID=$id NODE_PORT=$((BASE_PORT + id)) PEERS=$peers CLUSTER_TOKEN=$TOKEN \
      TSP_FILE=$TSP_FILE ROUNDS=$ROUNDS ANTS=$ants ITERATIONS_PER_ROUND=$ITERS ANT_THREADS=$THREADS \
      START_DELAY_MS=1000 PEER_WAIT_MS=30000 \
      java -jar "$JAR" > "$LOGS/n$n-r$rep-node$id.log" 2>&1 &
    pids+=($!)
  done
  local leader_log="$LOGS/n$n-r$rep-node$n.log" waited=0
  until grep -q "FIM:" "$leader_log" 2>/dev/null; do
    sleep 0.5; waited=$((waited + 1))
    if (( waited > TIMEOUT_S * 2 )); then echo "timeout esperando o líder (N=$n)" >&2; cleanup; return 1; fi
  done
  cleanup
  local ms best
  ms=$(grep -oE "rodada\(s\) em [0-9]+ ms" "$leader_log" | tail -1 | grep -oE "[0-9]+ ms" | grep -oE "[0-9]+")
  best=$(grep -oE "FIM: melhor rota = [0-9]+" "$leader_log" | grep -oE "[0-9]+$")
  echo "$ms $best"
}

run_baseline() {
  local nodes=$1 ants=$2
  NODES=$nodes TSP_FILE=$TSP_FILE ROUNDS=$ROUNDS ANTS=$ants ITERATIONS_PER_ROUND=$ITERS ANT_THREADS=$THREADS \
    java -cp "$JAR" br.pucminas.cd.aco.bench.SequentialBaseline \
    | sed -E 's/.*best=([0-9]+) time_ms=([0-9]+).*/\2 \1/'
}

echo "modo=$MODE rodadas=$ROUNDS iterações=$ITERS threads/nó=$THREADS instância=$TSP_FILE reps=$REPS núcleos=$cores"
echo "nos,formigas_por_no,formigas_por_rodada,cluster_ms,sequencial_ms,speedup,eficiencia,ms_por_rodada_cluster,melhor_cluster,melhor_sequencial" > "$CSV"

for n in $NODES_LIST; do
  ants=$(ants_for "$n")
  c_times=() c_best=() s_times=() s_best=()
  for rep in $(seq 1 "$REPS"); do
    read -r t b < <(run_cluster "$n" "$ants" "$rep"); c_times+=("$t"); c_best+=("$b")
    if [[ $MODE == strong ]]; then read -r t b < <(run_baseline 1 "$ANTS_TOTAL")
    else read -r t b < <(run_baseline "$n" "$ants"); fi
    s_times+=("$t"); s_best+=("$b")
    echo "  N=$n rep=$rep cluster=${c_times[-1]} ms sequencial=${s_times[-1]} ms" >&2
  done
  cm=$(printf '%s\n' "${c_times[@]}" | median)
  sm=$(printf '%s\n' "${s_times[@]}" | median)
  cb=$(printf '%s\n' "${c_best[@]}" | median)
  sb=$(printf '%s\n' "${s_best[@]}" | median)
  speedup=$(awk -v s="$sm" -v c="$cm" 'BEGIN {printf "%.2f", s / c}')
  eff=$(awk -v s="$speedup" -v n="$n" 'BEGIN {printf "%.2f", s / n}')
  per=$(awk -v c="$cm" -v r="$ROUNDS" 'BEGIN {printf "%.1f", c / r}')
  echo "$n,$ants,$((n * ants * ITERS)),$cm,$sm,$speedup,$eff,$per,$cb,$sb" >> "$CSV"
done

echo
column -s, -t < "$CSV"
echo
echo "CSV: $CSV"
