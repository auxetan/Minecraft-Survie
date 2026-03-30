#!/usr/bin/env bash
# ================================================================
#  start.sh — Demarre le serveur SurvivalCraft + Playit.gg
#  Compatible macOS (test) et Linux/Ubuntu (mini PC)
#  Usage : ./start.sh
# ================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/server"
PLUGIN_DIR="$SERVER_DIR/plugins"
SCREEN_MC="minecraft"
SCREEN_PLAYIT="playit"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── Detecter l'OS + binaire Playit ──────────────────────────────
OS="$(uname -s)"
ARCH="$(uname -m)"
IS_MAC=false
IS_LINUX=false
[[ "$OS" == "Darwin" ]] && IS_MAC=true
[[ "$OS" == "Linux"  ]] && IS_LINUX=true

if $IS_MAC; then
    [[ "$ARCH" == "arm64" ]] && PLAYIT_BIN_NAME="playit-darwin-aarch64" || PLAYIT_BIN_NAME="playit-darwin-x86_64"
else
    [[ "$ARCH" == "aarch64" ]] && PLAYIT_BIN_NAME="playit-linux-aarch64" || PLAYIT_BIN_NAME="playit-linux-amd64"
fi
PLAYIT_BIN="$SCRIPT_DIR/$PLAYIT_BIN_NAME"

# ── Verif prerequis ─────────────────────────────────────────────
command -v java &>/dev/null || error "Java non trouve. Lance d'abord : ./setup.sh"
[ -f "$SERVER_DIR/paper-1.21.4.jar" ] || error "PaperMC non trouve. Lance d'abord : ./setup.sh"

# ── JAVA_HOME selon l'OS ────────────────────────────────────────
if $IS_MAC; then
    if /usr/libexec/java_home -v 21 &>/dev/null 2>&1; then
        export JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)"
    elif /usr/libexec/java_home &>/dev/null 2>&1; then
        export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null)"
    fi
else
    if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
    elif [ -d "/usr/lib/jvm/java-21-openjdk-arm64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-arm64"
    fi
fi
export PATH="$JAVA_HOME/bin:$PATH"

JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/{print $2}' | cut -d'.' -f1)
[[ "$JAVA_VER" -ge 21 ]] || error "Java 21+ requis (detecte : Java $JAVA_VER)."

# ── screen disponible ? ─────────────────────────────────────────
USE_SCREEN=false
command -v screen &>/dev/null && USE_SCREEN=true

# ── Serveur deja en cours ? ─────────────────────────────────────
if $USE_SCREEN && screen -list 2>/dev/null | grep -q "$SCREEN_MC"; then
    warn "Le serveur tourne deja (screen '$SCREEN_MC')."
    echo -e "  Console MC      : ${YELLOW}screen -r $SCREEN_MC${NC}"
    echo -e "  Console Playit  : ${YELLOW}screen -r $SCREEN_PLAYIT${NC}"
    echo -e "  Tout arreter    : ${YELLOW}./stop.sh${NC}"
    exit 0
fi

# ── Build du plugin (auto) ──────────────────────────────────────
echo ""
info "Build du plugin SurvivalCore..."
cd "$SCRIPT_DIR/SurvivalCore"
JAVA_HOME="$JAVA_HOME" ./gradlew shadowJar --no-daemon 2>&1 \
    || { warn "Build echoue — demarrage avec l'ancien JAR."; }

PLUGIN_JAR=$(ls "$SCRIPT_DIR/SurvivalCore/build/libs/"SurvivalCore-*.jar 2>/dev/null | grep -v sources | head -1 || true)
if [ -n "$PLUGIN_JAR" ]; then
    cp "$PLUGIN_JAR" "$PLUGIN_DIR/"
    ok "Plugin deploye : $(basename "$PLUGIN_JAR")"
fi

# ── Flags JVM (Aikars) ──────────────────────────────────────────
# Sur Mac (test) : 2G max pour laisser de la RAM au systeme
# Sur Linux mini PC : 4G
if $IS_MAC; then
    XMX="2G"; XMS="1G"
else
    XMX="4G"; XMS="4G"
fi

JVM_FLAGS=(
    "-Xms${XMS}"
    "-Xmx${XMX}"
    "-XX:+UseG1GC"
    "-XX:+ParallelRefProcEnabled"
    "-XX:MaxGCPauseMillis=200"
    "-XX:+UnlockExperimentalVMOptions"
    "-XX:+DisableExplicitGC"
    "-XX:+AlwaysPreTouch"
    "-XX:G1NewSizePercent=30"
    "-XX:G1MaxNewSizePercent=40"
    "-XX:G1HeapRegionSize=8M"
    "-XX:G1ReservePercent=20"
    "-XX:G1HeapWastePercent=5"
    "-XX:G1MixedGCCountTarget=4"
    "-XX:InitiatingHeapOccupancyPercent=15"
    "-XX:G1MixedGCLiveThresholdPercent=90"
    "-XX:G1RSetUpdatingPauseTimePercent=5"
    "-XX:SurvivorRatio=32"
    "-XX:+PerfDisableSharedMem"
    "-XX:MaxTenuringThreshold=1"
    "-Dusing.aikars.flags=https://mcflags.emc.gs"
    "-Daikars.new.flags=true"
)

# ── Demarrage du serveur Minecraft ──────────────────────────────
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   Demarrage de SurvivalCraft...                      ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""

cd "$SERVER_DIR"
LOG_FILE="$SERVER_DIR/logs/start.log"
mkdir -p "$SERVER_DIR/logs"

if $USE_SCREEN; then
    screen -dmS "$SCREEN_MC" \
        java "${JVM_FLAGS[@]}" \
        -jar "paper-1.21.4.jar" \
        --nogui
    sleep 2
    if screen -list 2>/dev/null | grep -q "$SCREEN_MC"; then
        ok "Serveur Minecraft demarre (screen '$SCREEN_MC')."
    else
        error "Le serveur n'a pas demarre. Verifie server/logs/latest.log"
    fi
else
    # Sans screen : lancement en arriere-plan avec logs
    warn "screen non installe — lancement sans console interactive."
    nohup java "${JVM_FLAGS[@]}" \
        -jar "paper-1.21.4.jar" \
        --nogui > "$LOG_FILE" 2>&1 &
    MC_PID=$!
    echo $MC_PID > "$SCRIPT_DIR/.mc.pid"
    sleep 3
    if kill -0 $MC_PID 2>/dev/null; then
        ok "Serveur Minecraft demarre (PID $MC_PID)."
        info "Logs : tail -f server/logs/latest.log"
    else
        error "Le serveur n'a pas demarre. Verifie $LOG_FILE"
    fi
fi

# ── Demarrage de Playit.gg ──────────────────────────────────────
if [ -f "$PLAYIT_BIN" ]; then
    cd "$SCRIPT_DIR"
    if $USE_SCREEN; then
        if screen -list 2>/dev/null | grep -q "$SCREEN_PLAYIT"; then
            ok "Playit.gg tourne deja."
        else
            info "Demarrage du tunnel Playit.gg..."
            screen -dmS "$SCREEN_PLAYIT" "$PLAYIT_BIN"
            sleep 2
            screen -list 2>/dev/null | grep -q "$SCREEN_PLAYIT" \
                && ok "Playit.gg demarre." \
                || warn "Playit.gg n'a pas demarre. Lance-le manuellement : $PLAYIT_BIN"
        fi
    else
        nohup "$PLAYIT_BIN" > "$SCRIPT_DIR/playit.log" 2>&1 &
        PLAYIT_PID=$!
        echo $PLAYIT_PID > "$SCRIPT_DIR/.playit.pid"
        sleep 2
        if kill -0 $PLAYIT_PID 2>/dev/null; then
            ok "Playit.gg demarre (PID $PLAYIT_PID)."
            info "Logs Playit : tail -f $SCRIPT_DIR/playit.log"
        else
            warn "Playit.gg n'a pas demarre. Lance-le manuellement."
        fi
    fi
else
    warn "Playit.gg non installe. Lance ./setup.sh pour l'installer."
    warn "Sans Playit.gg, seuls les joueurs sur ton reseau local peuvent rejoindre."
fi

# ── IP locale ────────────────────────────────────────────────
LOCAL_IP="?"
if $IS_MAC; then
    LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "127.0.0.1")
else
    LOCAL_IP=$(hostname -I 2>/dev/null | awk '{print $1}' || echo "127.0.0.1")
fi

# ── Resume ──────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   SERVEUR EN LIGNE !                                 ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""
if $USE_SCREEN; then
    echo -e "  ${CYAN}Commandes utiles :${NC}"
    echo -e "    Console Minecraft  : ${YELLOW}screen -r $SCREEN_MC${NC}"
    echo -e "    Console Playit.gg  : ${YELLOW}screen -r $SCREEN_PLAYIT${NC}"
    echo -e "    Quitter la console : ${YELLOW}Ctrl+A puis D${NC}"
    echo -e "    Tout arreter       : ${YELLOW}./stop.sh${NC}"
else
    echo -e "  ${CYAN}Commandes utiles :${NC}"
    echo -e "    Logs serveur : ${YELLOW}tail -f server/logs/latest.log${NC}"
    echo -e "    Arreter      : ${YELLOW}./stop.sh${NC}"
fi
echo ""
echo -e "  ${CYAN}Connexion :${NC}"
echo -e "    Reseau local : ${BLUE}${LOCAL_IP}:25565${NC}"
echo -e "    Internet     : ${BLUE}Voir l'adresse dans les logs Playit.gg${NC}"
echo ""
if [ -f "$PLAYIT_BIN" ]; then
    echo -e "  ${YELLOW}PREMIERE FOIS avec Playit.gg ?${NC}"
    if $USE_SCREEN; then
        echo -e "  Tape ${YELLOW}screen -r playit${NC} et ouvre le lien affiche dans ton navigateur."
    else
        echo -e "  Tape ${YELLOW}tail -f playit.log${NC} et ouvre le lien affiche dans ton navigateur."
    fi
    echo -e "  Ajoute un tunnel Minecraft Java sur le port 25565."
fi
echo ""
