@echo off
rem Launches the GraalVM native-image build of XueXue with the runtime
rem staging it needs (java.home, skiko path, DPI scale auto-detection).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-native.ps1"
pause
