#!/usr/bin/env bash
# Operações comuns do projeto: build, testes, cluster local, simulação de falhas,
# verificação da exclusão mútua pelos logs, Docker Compose e LAN.
#
# Uso:  ./tp              menu interativo
#       ./tp <comando>    modo direto (ver ./tp help)
#
# Estado do cluster local fica em .run/ (pids, logs, parâmetros usados no "up").
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="$ROOT/.run"
LOGS="$RUN/logs"
JAR="$ROOT/target/aco-node.jar"
STATE="$RUN/cluster.env"

# ------------------------------------------------------------------ saída

if [[ -t 1 ]]; then
  B=$'\e[1m'; DIM=$'\e[2m'; RED=$'\e[31m'; GRN=$'\e[32m'; YEL=$'\e[33m'; CYN=$'\e[36m'; RST=$'\e[0m'
else
  B=; DIM=; RED=; GRN=; YEL=; CYN=; RST=
fi
info() { printf '%s==>%s %s\n' "$CYN" "$RST" "$*"; }
ok()   { printf '%s✔%s %s\n' "$GRN" "$RST" "$*"; }
warn() { printf '%s!%s %s\n' "$YEL" "$RST" "$*" >&2; }
die()  { printf '%s✘ %s%s\n' "$RED" "$*" "$RST" >&2; exit 1; }

# Pergunta com valor padrão: ask VAR "Pergunta" padrão
ask() {
  local __var=$1 prompt=$2 def=${3-} reply
  read -rp "$prompt ${DIM}[$def]${RST}: " reply
  printf -v "$__var" '%s' "${reply:-$def}"
}

# ------------------------------------------------------------------ build e testes

jar_stale() {
  [[ ! -f "$JAR" ]] || [[ -n "$(find "$ROOT/src" "$ROOT/pom.xml" -newer "$JAR" -print -quit)" ]]
}

cmd_build() {
  command -v mvn >/dev/null || die "Maven não encontrado (precisa de Maven 3.9+ e Java 21)"
  info "Compilando (mvn clean package -DskipTests)"
  (cd "$ROOT" && mvn -q -B clean package -DskipTests)
  ok "JAR gerado: ${JAR#"$ROOT"/}"
}

ensure_jar() {
  if jar_stale; then
    warn "JAR ausente ou mais antigo que o código; recompilando"
    cmd_build
  fi
}

cmd_test() {
  local filter=${1-}
  info "Rodando testes${filter:+ ($filter)}"
  (cd "$ROOT" && mvn -B test ${filter:+-Dtest="$filter"} -Dsurefire.failIfNoSpecifiedTests=false) \
    | grep -E 'Tests run:|FAIL|ERROR|BUILD' || true
  # Resumo a partir dos relatórios, independente do formato do Maven
  local reports="$ROOT/target/surefire-reports"
  [[ -d $reports ]] || die "sem relatórios de teste"
  awk -F'[:,]' '/^Tests run/ {t+=$2; f+=$4; e+=$6; s+=$8}
       END {printf "\nTotal: %d testes, %d falhas, %d erros, %d ignorados\n", t, f, e, s; exit (f+e>0)}' \
    "$reports"/*.txt && ok "Todos passaram" || die "Há testes falhando (ver target/surefire-reports/)"
  printf '%sDica: com o VS Code aberto, se aparecer "Unresolved compilation problem", rode\n' "$DIM"
  printf '"Java: Clean Java Language Server Workspace" e depois ./tp build.%s\n' "$RST"
}

# ------------------------------------------------------------------ cluster local

load_state() {
  [[ -f $STATE ]] || die "Nenhum cluster local criado. Use: ./tp up"
  # shellcheck source=/dev/null
  source "$STATE"
}

token() {
  if [[ -n ${CLUSTER_TOKEN-} ]]; then
    printf '%s' "$CLUSTER_TOKEN"
  elif [[ -f $ROOT/.env ]] && grep -q '^CLUSTER_TOKEN=' "$ROOT/.env"; then
    grep '^CLUSTER_TOKEN=' "$ROOT/.env" | cut -d= -f2-
  else
    mkdir -p "$RUN"
    [[ -f $RUN/token ]] || openssl rand -hex 16 > "$RUN/token" 2>/dev/null || date +%s%N | sha256sum | cut -c1-32 > "$RUN/token"
    cat "$RUN/token"
  fi
}

pid_of()   { cat "$RUN/n$1.pid" 2>/dev/null || true; }
is_alive() { local p; p=$(pid_of "$1"); [[ -n $p ]] && kill -0 "$p" 2>/dev/null; }
port_of()  { echo $((BASE_PORT + $1 - 1)); }

peers_of() {
  local self=$1 list=() id
  for ((id = 1; id <= NODES; id++)); do
    ((id == self)) || list+=("$id=localhost:$(port_of "$id")")
  done
  (IFS=,; echo "${list[*]}")
}

start_node() {
  local id=$1
  is_alive "$id" && { warn "N$id já está rodando (pid $(pid_of "$id"))"; return; }
  mkdir -p "$LOGS"
  printf '\n--- (re)início %s ---\n' "$(date +%T)" >> "$LOGS/n$id.log"
  env NODE_ID="$id" NODE_PORT="$(port_of "$id")" PEERS="$(peers_of "$id")" CLUSTER_TOKEN="$TOKEN" \
      ROUNDS="$ROUNDS" ANTS="$ANTS" TSP_CITIES="$CITIES" TSP_SEED="$SEED" TSP_FILE="$TSP_FILE" \
      START_DELAY_MS="$DELAY" \
      nohup java -jar "$JAR" >> "$LOGS/n$id.log" 2>&1 &
  echo $! > "$RUN/n$id.pid"
  disown "$!" # sem aviso "Killed" do bash quando o nó for derrubado
  ok "N$id iniciado (porta $(port_of "$id"), pid $!)"
}

usage_up() {
  cat <<EOF
./tp up [opções]
  -n NÓS          quantidade de nós            (padrão 4)
  -r RODADAS      rodadas do ACO               (padrão 30)
  -a FORMIGAS     formigas por nó por rodada   (padrão 40)
  -c CIDADES      cidades da instância         (padrão 60)
  -s SEMENTE      semente da instância         (padrão 42)
  -f ARQUIVO      instância TSPLIB (ex.: data/berlin52.tsp; ignora -c/-s)
  -d MS           espera do líder antes da 1ª rodada (padrão 3000)
  -p PORTA        porta do N1; N2 usa PORTA+1... (padrão 50051)
EOF
}

cmd_up() {
  local OPTIND opt
  NODES=4 ROUNDS=30 ANTS=40 CITIES=60 SEED=42 TSP_FILE="" DELAY=3000 BASE_PORT=50051
  while getopts "n:r:a:c:s:f:d:p:h" opt; do
    case $opt in
      n) NODES=$OPTARG ;; r) ROUNDS=$OPTARG ;; a) ANTS=$OPTARG ;; c) CITIES=$OPTARG ;;
      s) SEED=$OPTARG ;; f) TSP_FILE=$(realpath "$OPTARG") ;; d) DELAY=$OPTARG ;; p) BASE_PORT=$OPTARG ;;
      h) usage_up; return ;; *) usage_up; return 1 ;;
    esac
  done
  [[ $NODES =~ ^[0-9]+$ ]] && ((NODES >= 2)) || die "-n precisa ser >= 2"
  [[ -z $TSP_FILE || -f $TSP_FILE ]] || die "arquivo TSP não encontrado: $TSP_FILE"

  [[ -f $STATE ]] && cmd_down quiet
  ensure_jar
  rm -rf "$LOGS"; mkdir -p "$LOGS"
  TOKEN=$(token)
  # Atribuições simples (não "declare -p"): ao fazer source dentro de uma função, declare criaria locais
  local v
  for v in NODES ROUNDS ANTS CITIES SEED TSP_FILE DELAY BASE_PORT TOKEN; do printf '%s=%q\n' "$v" "${!v}"; done > "$STATE"

  info "Subindo $NODES nós: $ROUNDS rodadas, $ANTS formigas/nó, instância ${TSP_FILE:-aleatória ($CITIES cidades, semente $SEED)}"
  local id
  for ((id = 1; id <= NODES; id++)); do start_node "$id"; done
  printf '%sAcompanhe com ./tp status, ./tp logs -f, ./tp timeline cs%s\n' "$DIM" "$RST"
}

cmd_down() {
  [[ -f $STATE ]] || { [[ ${1-} == quiet ]] || warn "nenhum cluster local"; return 0; }
  load_state
  local id
  for ((id = 1; id <= NODES; id++)); do
    if is_alive "$id"; then kill "$(pid_of "$id")" 2>/dev/null || true; fi
    rm -f "$RUN/n$id.pid"
  done
  [[ ${1-} == quiet ]] || ok "Cluster local derrubado (logs mantidos em ${LOGS#"$ROOT"/})"
}

# Falha por crash (SIGKILL: sem shutdown hook, como queda de energia) ou parada limpa (SIGTERM)
cmd_kill() {
  load_state
  local id=${1:?informe o id do nó} sig=${2:-KILL}
  is_alive "$id" || die "N$id não está rodando"
  kill -s "$sig" "$(pid_of "$id")"
  rm -f "$RUN/n$id.pid"
  # Marca a queda no log com o último relógio do nó: um nó morto dentro da seção crítica nunca
  # loga SAIU, e o "check" precisa saber que ele deixou de segurá-la.
  local last_l; last_l=$(last_match "$id" '\[L=[0-9]+')
  printf '%s [N%s][L=%s] CAIU (./tp kill %s)\n' "$(date +%T.%3N)" "$id" "${last_l:-0}" "$sig" >> "$LOGS/n$id.log"
  ok "N$id derrubado com SIG$sig às $(date +%T.%3N)"
}

cmd_start() {
  load_state
  local id=${1:?informe o id do nó}
  ((id >= 1 && id <= NODES)) || die "N$id não faz parte do cluster (1..$NODES)"
  ensure_jar
  start_node "$id"
}

# Último valor que casa com um padrão no log do nó (ex.: líder visto, última rodada)
last_match() { grep -oE "$2" "$LOGS/n$1.log" 2>/dev/null | tail -1 | grep -oE '[0-9]+$' || true; }

leader_seen_by() { last_match "$1" 'Novo líder: N[0-9]+'; }

cmd_status() {
  load_state
  printf '%s%-4s %-8s %-7s %-7s %-8s %-8s %s%s\n' "$B" "Nó" "Estado" "Pid" "Porta" "Líder" "Rodada" "Relógio" "$RST"
  local id state
  for ((id = 1; id <= NODES; id++)); do
    if is_alive "$id"; then state="${GRN}vivo${RST}    "; else state="${RED}parado${RST}  "; fi
    printf '%-4s %s %-7s %-7s %-8s %-8s %s\n' "N$id" "$state" "$(pid_of "$id" || true)" "$(port_of "$id")" \
      "N$(leader_seen_by "$id")" "$(last_match "$id" 'Rodada [0-9]+')" \
      "$(last_match "$id" '\[L=[0-9]+' )"
  done
  grep -h "FIM:" "$LOGS"/*.log 2>/dev/null | tail -1 | cut -c1-140 || true
}

# Filtros por assunto para logs/timeline
filter_regex() {
  case ${1-} in
    ""|tudo)    echo '.' ;;
    cs|mutex)   echo 'ENTROU|SAIU|CAIU' ;;
    eleicao)    echo 'Eleição|líder|COORDINATOR|contestando|heartbeat|CAIU' ;;
    rodada)     echo 'LÍDER|Rodada|FIM' ;;
    rede)       echo 'inacessível|voltou a responder|Falha' ;;
    *)          echo "$1" ;;
  esac
}

cmd_logs() {
  load_state
  local follow=0 id="" filter=""
  while (($#)); do
    case $1 in -f) follow=1 ;; [0-9]*) id=$1 ;; *) filter=$1 ;; esac
    shift
  done
  local files=("$LOGS"/n*.log)
  [[ -n $id ]] && files=("$LOGS/n$id.log")
  local re; re=$(filter_regex "$filter")
  if ((follow)); then
    tail -n 20 -F "${files[@]}" | grep --line-buffered -E "$re|==>"
  else
    grep -hE "$re" "${files[@]}" | grep -E '^[0-9]{2}:' | sort -s -k1,1
  fi
}

# Todos os nós numa linha do tempo única, ordenada pelo relógio de Lamport.
cmd_timeline() {
  load_state
  local re; re=$(filter_regex "${1-}")
  grep -hE "$re" "$LOGS"/n*.log | grep -E '\[L=[0-9]+\]' |
    awk '{ match($0, /\[L=[0-9]+\]/); l = substr($0, RSTART + 3, RLENGTH - 4); printf "%012d %s\n", l, $0 }' |
    sort -s -k1,1 | cut -d' ' -f2-
}

# Verifica nos logs que nunca houve dois nós na seção crítica.
#
# Ordena pelo relógio de Lamport, não pela hora local: se B entrou depois que A saiu, existe a
# cadeia causal SAIU(A) -> REPLY -> ENTROU(B), então L(SAIU de A) < L(ENTROU de B), mesmo com nós em
# máquinas cujos relógios físicos discordam.
#
# Nó derrubado dentro da seção crítica (ENTROU seguido de CAIU, marcador gravado por ./tp kill) fica
# de fora: os outros entram porque *detectaram a falha por timeout*, e não há mensagem do nó morto
# que ligue os eventos causalmente, logo Lamport não consegue ordená-los.
cmd_check() {
  load_state
  local entries
  entries=$(cat "$LOGS"/n*.log | grep -cE 'ENTROU na seção' || true)
  ((entries > 0)) || die "nenhuma entrada na seção crítica nos logs ainda"
  local f
  for f in "$LOGS"/n*.log; do
    # Por nó, na ordem do próprio log: descarta ENTROU que terminou em CAIU.
    grep -E 'ENTROU|SAIU|CAIU' "$f" | awk '
      /ENTROU/ { if (pending != "") print pending; pending = $0; next }
      /CAIU/   { if (pending != "") print "CRASH " pending; pending = ""; next }
               { if (pending != "") print pending; pending = ""; print }
      END      { if (pending != "") print pending }'
  done |
    awk '{ crash = ($1 == "CRASH")
           match($0, /\[L=[0-9]+\]/); l = substr($0, RSTART + 3, RLENGTH - 4)
           match($0, /\[N[0-9]+\]/); n = substr($0, RSTART + 2, RLENGTH - 3)
           printf "%012d %d %s %d\n", l, ($0 ~ /ENTROU/), n, crash }' |
    sort -k1,1 -k2,2 |
    awk -v ok="$GRN" -v bad="$RED" -v rst="$RST" '
      $4 == 1 { printf "nota: N%s caiu dentro da seção crítica (L=%d); os outros seguiram sem ele\n", $3, $1; next }
      $2 == 1 { if (holder != "") { printf "%sVIOLAÇÃO: N%s entrou (L=%d) com N%s dentro%s\n", bad, $3, $1, holder, rst; v++ }
                holder = $3; n++; next }
              { if (holder == $3) holder = "" }
      END { if (v) exit 1; printf "%s✔ Exclusão mútua respeitada em %d entradas na seção crítica%s\n", ok, n, rst }'
}

# ------------------------------------------------------------------ demonstrações roteirizadas

wait_until() { # wait_until SEGUNDOS descrição comando...
  local secs=$1 what=$2; shift 2
  local end=$((SECONDS + secs))
  printf '%s   aguardando %s ' "$DIM" "$what"
  until "$@"; do
    ((SECONDS < end)) || { printf '%s\n' "$RST"; die "tempo esgotado aguardando $what"; }
    printf '.'; sleep 0.5
  done
  printf '%s\n' "$RST"
}

all_alive_see_leader() {
  local want=$1 id
  for ((id = 1; id <= NODES; id++)); do
    is_alive "$id" || continue
    [[ $(leader_seen_by "$id") == "$want" ]] || return 1
  done
}

finished() { grep -q "FIM:" "$LOGS"/n*.log 2>/dev/null; }

demo_failover() {
  info "Demo: queda do líder e reeleição (Bully + detector de falha por heartbeat)"
  cmd_up -n "${1:-4}" -r 500 -a 40 -d 2000
  load_state
  wait_until 30 "todos elegerem N$NODES" all_alive_see_leader "$NODES"
  ok "Líder inicial: N$NODES (maior id)"
  sleep 4
  local t0=$SECONDS
  cmd_kill "$NODES"
  wait_until 30 "nova eleição" all_alive_see_leader "$((NODES - 1))"
  ok "Novo líder: N$((NODES - 1)) em ~$((SECONDS - t0)) s (3 heartbeats perdidos + eleição)"
  printf '\n%sEventos de eleição (ordem de Lamport):%s\n' "$B" "$RST"
  cmd_timeline eleicao | tail -15
  printf '\n'; info "Religando N$NODES: ele deve vencer de novo (maior id)"
  cmd_start "$NODES"
  wait_until 30 "N$NODES reassumir" all_alive_see_leader "$NODES"
  ok "N$NODES reassumiu a liderança"
  cmd_check
  printf '%sCluster continua rodando. Encerre com ./tp down%s\n' "$DIM" "$RST"
}

demo_mutex() {
  info "Demo: exclusão mútua (Ricart-Agrawala) ordenada por Lamport"
  cmd_up -n "${1:-4}" -r 5 -a 60 -c 80 -d 2000
  wait_until 120 "as rodadas terminarem" finished
  printf '\n%sSeção crítica, todos os nós, ordem de Lamport:%s\n' "$B" "$RST"
  cmd_timeline cs
  printf '\n'; cmd_check
  grep -h "FIM:" "$LOGS"/*.log | tail -1 | cut -c1-120
  cmd_down
}

cmd_demo() {
  case ${1-} in
    failover) demo_failover "${2-}" ;;
    mutex)    demo_mutex "${2-}" ;;
    *) die "uso: ./tp demo failover|mutex [nós]" ;;
  esac
}

# ------------------------------------------------------------------ Docker e LAN

cmd_docker() {
  command -v docker >/dev/null || die "Docker não encontrado"
  [[ -f $ROOT/.env ]] || { warn "criando .env com token aleatório"; echo "CLUSTER_TOKEN=$(token)" > "$ROOT/.env"; }
  local dc=(docker compose --project-directory "$ROOT")
  case ${1-} in
    up)    "${dc[@]}" up --build -d && ok "4 nós no Docker. Veja: ./tp docker logs" ;;
    down)  "${dc[@]}" down ;;
    stop)  "${dc[@]}" stop "node${2:?id}" ;;
    kill)  "${dc[@]}" kill "node${2:?id}" ;;
    start) "${dc[@]}" start "node${2:?id}" ;;
    logs)  "${dc[@]}" logs -f --no-log-prefix "${@:2}" ;;
    ps)    "${dc[@]}" ps ;;
    *) die "uso: ./tp docker up|down|ps|logs [nodeN]|stop N|kill N|start N" ;;
  esac
}

# Imprime o comando para rodar um nó numa máquina da LAN.
cmd_lan() {
  local self=${1:?uso: ./tp lan <meu_id> <ip1> <ip2> ... (ip na ordem dos ids)}; shift
  (($#)) || die "informe os IPs de todos os nós, na ordem dos ids (o próprio incluso)"
  local peers=() i=1 ip
  for ip in "$@"; do
    ((i == self)) || peers+=("$i=$ip:50051")
    i=$((i + 1))
  done
  printf '%s# Rode na máquina do N%s (libere a porta 50051 no firewall):%s\n' "$DIM" "$self" "$RST"
  printf 'CLUSTER_TOKEN=%s NODE_ID=%s PEERS="%s" java -jar aco-node.jar\n' "$(token)" "$self" "$(IFS=,; echo "${peers[*]}")"
}

# ------------------------------------------------------------------ menu interativo

pick_node() { local id; ask id "Id do nó" "${1:-1}"; echo "$id"; }

# Executa uma ação do menu em subshell: um die/exit dentro dela não fecha o menu.
act() { ( "$@" ) || warn "a operação terminou com erro"; }

menu() {
  local opts=(
    "Compilar (gera o JAR)"
    "Rodar todos os testes"
    "Rodar um teste específico"
    "Subir cluster local (escolher parâmetros)"
    "Status do cluster"
    "Derrubar um nó (crash)"
    "Religar um nó"
    "Ver logs (filtro)"
    "Linha do tempo por Lamport"
    "Verificar exclusão mútua nos logs"
    "Demo: queda do líder"
    "Demo: exclusão mútua"
    "Derrubar cluster local"
    "Docker Compose: subir 4 nós"
    "Docker Compose: derrubar"
    "Comando para rodar na LAN"
    "Sair"
  )
  local PS3=$'\n'"${B}Escolha uma opção:${RST} " choice n r a c f
  while true; do
    printf '\n%sACO distribuído — operações%s\n' "$B" "$RST"
    select choice in "${opts[@]}"; do
      case $REPLY in
        1) act cmd_build ;;
        2) act cmd_test ;;
        3) ask f "Classe ou Classe#metodo (ex.: RicartAgrawalaTest)" "RicartAgrawalaSafetyTest"; act cmd_test "$f" ;;
        4) ask n "Nós" 4; ask r "Rodadas" 30; ask a "Formigas por nó" 40; ask c "Cidades (ignorado se usar arquivo)" 60
           ask f "Arquivo TSPLIB (vazio = instância aleatória)" ""
           act cmd_up -n "$n" -r "$r" -a "$a" -c "$c" ${f:+-f "$f"} ;;
        5) act cmd_status ;;
        6) act cmd_kill "$(pick_node 4)" ;;
        7) act cmd_start "$(pick_node 4)" ;;
        8) ask f "Filtro: tudo | cs | eleicao | rodada | rede | regex" "tudo"; act cmd_logs "$f" | tail -40 ;;
        9) ask f "Filtro: tudo | cs | eleicao | rodada | rede | regex" "cs"; act cmd_timeline "$f" | tail -40 ;;
        10) act cmd_check ;;
        11) ask n "Nós" 4; act demo_failover "$n" ;;
        12) ask n "Nós" 4; act demo_mutex "$n" ;;
        13) act cmd_down ;;
        14) act cmd_docker up ;;
        15) act cmd_docker down ;;
        16) ask n "Meu id" 1; ask f "IPs de todos, na ordem dos ids (separados por espaço)" "192.168.0.11 192.168.0.12"
            # shellcheck disable=SC2086
            act cmd_lan "$n" $f ;;
        17) return ;;
        *) warn "opção inválida" ;;
      esac
      break
    done
  done
}

usage() {
  cat <<EOF
${B}./tp${RST} — operações do projeto (sem argumentos abre o menu interativo)

${B}Build e testes${RST}
  build                         compila e gera target/aco-node.jar
  test [Classe[#metodo]]        roda os testes (todos ou um filtro)

${B}Cluster local${RST} (processos Java nesta máquina; estado em .run/)
  up [-n 4 -r 30 -a 40 ...]     sobe o cluster (./tp up -h para as opções)
  status                        nós vivos, líder visto, rodada e relógio de cada um
  kill <id> [TERM]              derruba um nó (padrão: SIGKILL = crash)
  start <id>                    religa um nó com os mesmos parâmetros
  logs [id] [filtro] [-f]       logs por hora; filtros: cs, eleicao, rodada, rede ou regex
  timeline [filtro]             todos os nós ordenados pelo relógio de Lamport
  check                         verifica nos logs que nunca houve 2 nós na seção crítica
  down                          derruba todos os nós

${B}Demonstrações${RST}
  demo failover [nós]           elege, derruba o líder, mede a reeleição, religa
  demo mutex [nós]              roda algumas rodadas e mostra a seção crítica em ordem de Lamport

${B}Outros${RST}
  docker up|down|ps|logs|stop N|kill N|start N    atalhos do Docker Compose
  lan <meu_id> <ip1> <ip2> ...  imprime o comando para rodar na LAN
  token                         gera/mostra o token do cluster
  clean                         apaga .run/ (logs e estado)

Exemplos:
  ./tp up -n 3 -r 10 && ./tp status
  ./tp kill 3 && sleep 5 && ./tp status
  ./tp timeline eleicao
  ./tp demo failover
EOF
}

main() {
  cd "$ROOT"
  local cmd=${1-menu}; shift || true
  case $cmd in
    menu)      menu ;;
    build)     cmd_build ;;
    test)      cmd_test "$@" ;;
    up)        cmd_up "$@" ;;
    down)      cmd_down ;;
    status)    cmd_status ;;
    kill)      cmd_kill "$@" ;;
    start)     cmd_start "$@" ;;
    logs)      cmd_logs "$@" ;;
    timeline)  cmd_timeline "$@" ;;
    check)     cmd_check ;;
    demo)      cmd_demo "$@" ;;
    docker)    cmd_docker "$@" ;;
    lan)       cmd_lan "$@" ;;
    token)     token; echo ;;
    clean)     cmd_down quiet; rm -rf "$RUN"; ok ".run/ removido" ;;
    help|-h|--help) usage ;;
    *) usage; exit 1 ;;
  esac
}

main "$@"
