@echo off
rem Dev launcher: compiles and runs the tray app (dashboard + bridge) without packaging.
rem Right-click the tray icon -> Open Dashboard, or browse http://localhost:20080/
cd /d "%~dp0"
if not exist out mkdir out
javac -d out src\main\java\geoplanph\*.java
if errorlevel 1 (echo BUILD FAILED & exit /b 1)
xcopy /e /y /i src\main\resources\geoplanph out\geoplanph >nul
start "" javaw -cp out geoplanph.TrayApp
