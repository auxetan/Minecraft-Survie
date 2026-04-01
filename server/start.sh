#!/bin/bash
# ================================================================
#  SurvivalCraft — Script de démarrage
#  Serveur : Paper 1.21.4 | Machine : Ace Magician (Intel N100)
#  RAM allouée : 2 Go min / 4 Go max (ajustable via Xms/Xmx)
# ================================================================

cd "$(dirname "$0")"

# ── Vérifie que Java 21+ est disponible ──────────────────────────
JAVA_CMD="java"
if [ -n "$JAVA_HOME" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
fi

JAVA_VERSION=$("$JAVA_CMD" -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1)
if [ "$JAVA_VERSION" -lt 21 ] 2>/dev/null; then
    echo "[ERREUR] Java 21+ requis. Version détectée : $JAVA_VERSION"
    echo "Installe Java 21 : brew install openjdk@21"
    exit 1
fi

# ── Détecte l'IP LAN et met à jour server.properties ────────────
LAN_IP=$(ipconfig getifaddr en0 2>/dev/null \
      || ipconfig getifaddr en1 2>/dev/null \
      || hostname -I 2>/dev/null | awk '{print $1}' \
      || echo "127.0.0.1")

# Remplace SERVER_IP ou l'ancienne IP par l'IP détectée
if grep -q "SERVER_IP" server.properties 2>/dev/null; then
    sed -i.bak "s|SERVER_IP|$LAN_IP|g" server.properties
    echo "[INFO] IP mise à jour dans server.properties : $LAN_IP"
fi

# ── Démarre le serveur HTTP pour le resource pack ───────────────
echo "[INFO] Démarrage du serveur HTTP resource pack (port 8080)..."
if command -v python3 &>/dev/null; then
    python3 -m http.server 8080 --bind 0.0.0.0 > /dev/null 2>&1 &
    PACK_PID=$!
    echo "[INFO] Resource pack : http://$LAN_IP:8080/Faithless.zip"
else
    echo "[WARN] python3 non trouvé — resource pack non servi automatiquement"
    PACK_PID=""
fi

# ── Flags JVM (Aikar flags — optimisés Paper + N100) ────────────
JVM_FLAGS=(
    -Xms2G -Xmx4G
    -XX:+UseG1GC
    -XX:+ParallelRefProcEnabled
    -XX:MaxGCPauseMillis=200
    -XX:+UnlockExperimentalVMOptions
    -XX:+DisableExplicitGC
    -XX:+AlwaysPreTouch
    -XX:G1NewSizePercent=30
    -XX:G1MaxNewSizePercent=40
    -XX:G1HeapRegionSize=8M
    -XX:G1ReservePercent=20
    -XX:G1HeapWastePercent=5
    -XX:G1MixedGCCountTarget=4
    -XX:InitiatingHeapOccupancyPercent=15
    -XX:G1MixedGCLiveThresholdPercent=90
    -XX:G1RSetUpdatingPauseTimePercent=5
    -XX:SurvivorRatio=32
    -XX:+PerfDisableSharedMem
    -XX:MaxTenuringThreshold=1
    -Dusing.aikars.flags=https://mcflags.emc.gs
    -Daikars.new.flags=true
)

echo "[INFO] Démarrage de Paper 1.21.4..."
echo "[INFO] Java : $("$JAVA_CMD" -version 2>&1 | head -1)"
echo ""

# ── Lancement ───────────────────────────────────────────────────
"$JAVA_CMD" "${JVM_FLAGS[@]}" -jar paper-1.21.4.jar --nogui

# ── Nettoyage à l'arrêt ─────────────────────────────────────────
if [ -n "$PACK_PID" ]; then
    echo "[INFO] Arrêt du serveur HTTP resource pack..."
    kill "$PACK_PID" 2>/dev/null
fi

echo "[INFO] Serveur arrêté."
