#!/usr/bin/env bash
# ================================================================
#  start.sh — Demarre le serveur SurvivalCraft + Playit.gg
#  Usage : ./start.sh [--dev]
#
#  Le serveur Minecraft tourne dans   : screen -r minecraft
#  Le tunnel Playit.gg tourne dans    : screen -r playit
#  Tout arreter proprement            : ./stop.sh
# ================================================================
set -euo pipefail

# ── Detection OS / Architecture ─────────────────────────────────
OS="$(uname -s)"
ARCH="$(uname -m)"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/server"
PLUGIN_DIR="$SERVER_DIR/plugins"
SCREEN_MC="minecraft"
SCREEN_PLAYIT="playit"
PLAYIT_PUBLIC_URL="listed-broadway.gl.joinmc.link"

# ── Playit binary — dynamique selon OS+ARCH ─────────────────────
if [ "$OS" = "Darwin" ]; then
    if [ "$ARCH" = "arm64" ]; then
        PLAYIT_BIN="$SCRIPT_DIR/playit-darwin-aarch64"
    else
        PLAYIT_BIN="$SCRIPT_DIR/playit-darwin-amd64"
    fi
else
    PLAYIT_BIN="$SCRIPT_DIR/playit-linux-amd64"
fi

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── IP locale (portable) — détectée tôt pour le pack de textures ─
if [ "$OS" = "Darwin" ]; then
    LOCAL_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "127.0.0.1")"
else
    LOCAL_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
fi
MODPAGE_PORT=8080

# ── Verif prerequis ─────────────────────────────────────────────
command -v java &>/dev/null   || error "Java non trouve. Lance d'abord : ./setup.sh"
command -v screen &>/dev/null || error "screen non trouve. Lance d'abord : ./setup.sh"
[ -f "$SERVER_DIR/paper-1.21.4.jar" ] || error "PaperMC non trouve. Lance d'abord : ./setup.sh"

# ── Java 21 ─────────────────────────────────────────────────────
if [ "$OS" = "Darwin" ]; then
    JAVA_HOME_DETECTED="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [ -z "$JAVA_HOME_DETECTED" ] && command -v brew &>/dev/null; then
        # Essayer les chemins brew courants
        for candidate in \
            "$(brew --prefix openjdk@21 2>/dev/null)/libexec/openjdk.jdk/Contents/Home" \
            "$(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"; do
            if [ -d "$candidate" ]; then
                JAVA_HOME_DETECTED="$candidate"
                break
            fi
        done
    fi
    if [ -n "$JAVA_HOME_DETECTED" ]; then
        export JAVA_HOME="$JAVA_HOME_DETECTED"
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
else
    if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
fi

JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/{print $2}' | cut -d'.' -f1)
[[ "$JAVA_VER" -ge 21 ]] || error "Java 21+ requis (detecte : $JAVA_VER)."

# ── RAM allocation — --dev flag ou macOS = 2G, sinon 4G ─────────
if [ "${1:-}" = "--dev" ] || [ "$OS" = "Darwin" ]; then
    RAM="2G"
    info "Mode dev / macOS : RAM limitee a 2G."
else
    RAM="4G"
fi

# ── Serveur deja en cours ? ─────────────────────────────────────
if screen -list 2>/dev/null | grep -q "$SCREEN_MC"; then
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
JAVA_HOME="${JAVA_HOME:-}" ./gradlew shadowJar --no-daemon 2>&1 \
    || { warn "Build echoue — demarrage avec l'ancien JAR."; }

PLUGIN_JAR=$(ls "$SCRIPT_DIR/SurvivalCore/build/libs/"SurvivalCore-*.jar 2>/dev/null | grep -v sources | head -1)
if [ -n "$PLUGIN_JAR" ]; then
    cp "$PLUGIN_JAR" "$PLUGIN_DIR/"
    ok "Plugin deploye : $(basename "$PLUGIN_JAR")"
fi

# ── Demarrage ANTICIPÉ de Playit.gg (avant MC pour récupérer l'URL HTTP) ──
PLAYIT_HTTP_URL=""
if [ -f "$PLAYIT_BIN" ]; then
    if screen -list 2>/dev/null | grep -q "$SCREEN_PLAYIT"; then
        ok "Playit.gg tourne deja — tentative de récupération URL HTTP..."
    else
        info "Démarrage anticipé de Playit.gg (pour URL pack de textures)..."
        screen -dmS "$SCREEN_PLAYIT" "$PLAYIT_BIN"
        info "Attente connexion Playit.gg (5 s)..."
        sleep 5
    fi

    # Interroger l'API locale du démon playit (port 5174, présent sur v0.15+)
    # Cherche un tunnel HTTP/HTTPS dont le local_port est MODPAGE_PORT (8080)
    PLAYIT_API_RESP=$(curl -s --max-time 2 "http://127.0.0.1:5174/api/v1/tunnels" 2>/dev/null \
        || curl -s --max-time 2 "http://127.0.0.1:5174/api/tunnels" 2>/dev/null || echo "")

    if [ -n "$PLAYIT_API_RESP" ]; then
        PLAYIT_HTTP_URL=$(echo "$PLAYIT_API_RESP" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    tunnels = data if isinstance(data, list) else data.get('tunnels', data.get('data', []))
    for t in tunnels:
        proto = str(t.get('proto', t.get('protocol', ''))).lower()
        local_port = t.get('local_port', t.get('localPort', 0))
        if ('http' in proto) and int(local_port) == $MODPAGE_PORT:
            addr = t.get('public_url', t.get('publicUrl', t.get('address', '')))
            if addr and not addr.startswith('http'):
                addr = 'http://' + addr
            print(addr.rstrip('/'))
            sys.exit(0)
except Exception:
    pass
" 2>/dev/null || echo "")
    fi

    # Fallback: lire depuis server/rp_url.txt si l'utilisateur l'a créé manuellement
    if [ -z "$PLAYIT_HTTP_URL" ] && [ -f "$SERVER_DIR/rp_url.txt" ]; then
        PLAYIT_HTTP_URL=$(cat "$SERVER_DIR/rp_url.txt" | tr -d '[:space:]')
        [ -n "$PLAYIT_HTTP_URL" ] && ok "URL HTTP depuis rp_url.txt : $PLAYIT_HTTP_URL"
    fi
else
    warn "Playit.gg non installé. Lance ./setup.sh pour l'installer."
fi

# ── Pack de textures Faithless — zip + SHA1 + server.properties ─
FAITHLESS_DIR="$SCRIPT_DIR/Faithless"
FAITHLESS_ZIP="$SERVER_DIR/faithless.zip"
if [ -d "$FAITHLESS_DIR" ]; then
    info "Compression du pack de textures Faithless..."
    rm -f "$FAITHLESS_ZIP"
    (cd "$FAITHLESS_DIR" && zip -r "$FAITHLESS_ZIP" . -x "*.DS_Store" -x "__MACOSX/*" > /dev/null 2>&1)
    if [ "$OS" = "Darwin" ]; then
        FAITHLESS_SHA1=$(shasum -a 1 "$FAITHLESS_ZIP" | awk '{print $1}')
    else
        FAITHLESS_SHA1=$(sha1sum "$FAITHLESS_ZIP" | awk '{print $1}')
    fi

    # Choisir l'URL : HTTP tunnel (internet) > variable env > LAN
    if [ -n "$PLAYIT_HTTP_URL" ]; then
        RP_URL="${PLAYIT_HTTP_URL}/resourcepack"
        ok "Pack de textures via tunnel HTTP Playit : $RP_URL"
    elif [ -n "${PUBLIC_RP_URL:-}" ]; then
        RP_URL="$PUBLIC_RP_URL"
        ok "Pack de textures via PUBLIC_RP_URL : $RP_URL"
    else
        RP_URL="http://${LOCAL_IP}:${MODPAGE_PORT}/resourcepack"
        warn "Pack de textures accessible LAN seulement : $RP_URL"
        warn "Pour internet : crée un tunnel HTTP dans playit.gg (port $MODPAGE_PORT)"
        warn "  Puis sauvegarde l'URL dans server/rp_url.txt"
    fi

    PROPS="$SERVER_DIR/server.properties"
    grep -v "^resource-pack" "$PROPS" > "$PROPS.tmp"
    cat >> "$PROPS.tmp" << RPEOF
resource-pack=$RP_URL
resource-pack-sha1=$FAITHLESS_SHA1
resource-pack-prompt={"text":"Télécharge le resource pack SurvivalCraft !","color":"aqua"}
require-resource-pack=false
RPEOF
    mv "$PROPS.tmp" "$PROPS"
    ok "SHA1 : $FAITHLESS_SHA1"
else
    warn "Dossier Faithless/ introuvable — pack de textures non configuré."
fi

# ── Demarrage du serveur Minecraft ──────────────────────────────
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   Demarrage de SurvivalCraft...                      ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""

# Flags JVM Aikar — adaptes a la RAM disponible
JVM_FLAGS=(
    "-Xms${RAM}"
    "-Xmx${RAM}"
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

cd "$SERVER_DIR"
screen -dmS "$SCREEN_MC" \
    java "${JVM_FLAGS[@]}" \
    -jar "paper-1.21.4.jar" \
    --nogui

sleep 2
if screen -list 2>/dev/null | grep -q "$SCREEN_MC"; then
    ok "Serveur Minecraft demarre."
else
    error "Le serveur n'a pas demarre. Verifie server/logs/latest.log"
fi

# Vérification finale de Playit.gg (démarré en amont)
if [ -f "$PLAYIT_BIN" ]; then
    if screen -list 2>/dev/null | grep -q "$SCREEN_PLAYIT"; then
        ok "Playit.gg actif."
    else
        warn "Playit.gg n'a pas demarre. Lance-le manuellement : $PLAYIT_BIN"
    fi
fi

# ── Resume ──────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   SERVEUR EN LIGNE !                                 ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${CYAN}Commandes utiles :${NC}"
echo -e "    Console Minecraft  : ${YELLOW}screen -r $SCREEN_MC${NC}"
echo -e "    Console Playit.gg  : ${YELLOW}screen -r $SCREEN_PLAYIT${NC}"
echo -e "    Quitter la console : ${YELLOW}Ctrl+A puis D${NC}"
echo -e "    Tout arreter       : ${YELLOW}./stop.sh${NC}"
echo -e "    Rebuild le plugin  : ${YELLOW}./build.sh${NC}"
echo ""
echo -e "  ${CYAN}Connexion :${NC}"
echo -e "    Reseau local : ${BLUE}${LOCAL_IP}:25565${NC}"
echo -e "    Internet     : ${GREEN}${PLAYIT_PUBLIC_URL}${NC}"
echo ""
echo -e "  ${CYAN}Pack de textures (Faithless) :${NC}"
echo -e "    URL : ${BLUE}${RP_URL:-non configuré}${NC}"
echo ""
echo -e "  ${CYAN}Carte 3D BlueMap :${NC}"
echo -e "    Local  : ${BLUE}http://${LOCAL_IP}:8123${NC}"
[ -n "$PLAYIT_HTTP_URL" ] && echo -e "    Web    : ${GREEN}${PLAYIT_HTTP_URL/:8080/:8123}${NC}"
echo ""
echo -e "  ${YELLOW}Partage cette adresse a tes potes :${NC}"
echo -e "    ${GREEN}${PLAYIT_PUBLIC_URL}${NC}"
echo ""
