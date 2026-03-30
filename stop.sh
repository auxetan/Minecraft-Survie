#!/usr/bin/env bash
# ================================================================
#  stop.sh — Arret propre du serveur Minecraft + Playit.gg
#  Compatible macOS et Linux
# ================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCREEN_MC="minecraft"
SCREEN_PLAYIT="playit"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }

USE_SCREEN=false
command -v screen &>/dev/null && USE_SCREEN=true

MC_RUNNING=false
PLAYIT_RUNNING=false

if $USE_SCREEN; then
    screen -list 2>/dev/null | grep -q "$SCREEN_MC" && MC_RUNNING=true
    screen -list 2>/dev/null | grep -q "$SCREEN_PLAYIT" && PLAYIT_RUNNING=true
else
    # Mode PID file (macOS sans screen)
    [ -f "$SCRIPT_DIR/.mc.pid" ] && kill -0 "$(cat "$SCRIPT_DIR/.mc.pid")" 2>/dev/null && MC_RUNNING=true
    [ -f "$SCRIPT_DIR/.playit.pid" ] && kill -0 "$(cat "$SCRIPT_DIR/.playit.pid")" 2>/dev/null && PLAYIT_RUNNING=true
fi

if [ "$MC_RUNNING" = false ] && [ "$PLAYIT_RUNNING" = false ]; then
    warn "Aucun serveur en cours."
    exit 0
fi

# ── Arret Minecraft ─────────────────────────────────────────────
if [ "$MC_RUNNING" = true ]; then
    info "Arret du serveur Minecraft..."
    if $USE_SCREEN; then
        screen -S "$SCREEN_MC" -p 0 -X stuff "say Le serveur s'arrete dans 5 secondes...$(printf '\r')"
        sleep 5
        screen -S "$SCREEN_MC" -p 0 -X stuff "stop$(printf '\r')"
        for i in $(seq 1 30); do
            sleep 1
            if ! screen -list 2>/dev/null | grep -q "$SCREEN_MC"; then
                ok "Serveur Minecraft arrete."
                break
            fi
            if [ "$i" -eq 30 ]; then
                warn "Timeout — fermeture forcee."
                screen -S "$SCREEN_MC" -X quit 2>/dev/null || true
            fi
        done
    else
        MC_PID=$(cat "$SCRIPT_DIR/.mc.pid" 2>/dev/null || echo "")
        if [ -n "$MC_PID" ]; then
            # Envoyer 'stop' via RCON-cli n'est pas dispo, on tue proprement
            kill -TERM "$MC_PID" 2>/dev/null || true
            sleep 8
            kill -0 "$MC_PID" 2>/dev/null && kill -KILL "$MC_PID" 2>/dev/null || true
            rm -f "$SCRIPT_DIR/.mc.pid"
        fi
        ok "Serveur Minecraft arrete."
    fi
fi

# ── Arret Playit.gg ────────────────────────────────────────────
if [ "$PLAYIT_RUNNING" = true ]; then
    info "Arret du tunnel Playit.gg..."
    if $USE_SCREEN; then
        screen -S "$SCREEN_PLAYIT" -X quit 2>/dev/null || true
    else
        PLAYIT_PID=$(cat "$SCRIPT_DIR/.playit.pid" 2>/dev/null || echo "")
        [ -n "$PLAYIT_PID" ] && kill -TERM "$PLAYIT_PID" 2>/dev/null || true
        rm -f "$SCRIPT_DIR/.playit.pid"
    fi
    sleep 1
    ok "Playit.gg arrete."
fi

echo ""
ok "Tout est arrete proprement."
echo ""
