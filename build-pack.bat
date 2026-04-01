@echo off
REM ================================================================
REM  build-pack.bat — Crée faithless.zip pour le resource pack
REM  Usage : double-cliquer ou lancer depuis le dossier du projet
REM  Nécessaire sur Windows (start.sh fait ça automatiquement sur Mac/Linux)
REM ================================================================
cd /d "%~dp0"

echo [INFO] Compression du resource pack Faithless...
if exist "server\faithless.zip" del /q "server\faithless.zip"
powershell -Command "Compress-Archive -Path 'Faithless\*' -DestinationPath 'server\faithless.zip' -Force"

if exist "server\faithless.zip" (
    echo [OK] faithless.zip cree dans server\
) else (
    echo [ERREUR] Echec de la creation de faithless.zip
    pause
    exit /b 1
)
