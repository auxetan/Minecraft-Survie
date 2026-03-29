#!/usr/bin/env bash
# ================================================================
#  build.sh — Rebuild et déploie SurvivalCore sans redémarrer
#  Si le serveur est en cours, utilise /reload confirm automatiquement
# ================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/server"
SCREEN_NAME="minecraft"

BLUE='\033[0;34m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# Java 21
if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
    export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

info "Build de SurvivalCore..."
cd "$SCRIPT_DIR/SurvivalCore"
JAVA_HOME="$JAVA_HOME" ./gradlew shadowJar --quiet \
    || error "Build échoué."

PLUGIN_JAR=$(ls "$SCRIPT_DIR/SurvivalCore/build/libs/"SurvivalCore-*.jar 2>/dev/null | head -1)
[ -z "$PLUGIN_JAR" ] && error "JAR introuvable après le build."

cp "$PLUGIN_JAR" "$SERVER_DIR/plugins/"
ok "$(basename "$PLUGIN_JAR") → $SERVER_DIR/plugins/"

# Reload automatique si le serveur tourne
if screen -list 2>/dev/null | grep -q "$SCREEN_NAME"; then
    warn "Serveur en cours. Envoi de 'reload confirm'..."
    warn "(Note : /reload peut causer des instabilités. Redémarre avec ./stop.sh + ./start.sh si besoin.)"
    screen -S "$SCREEN_NAME" -p 0 -X stuff "reload confirm$(printf '\r')"
    ok "Reload envoyé."
else
    info "Serveur arrêté — le plugin sera chargé au prochain ./start.sh"
fi
