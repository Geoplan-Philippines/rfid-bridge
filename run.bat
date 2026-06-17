@echo off
rem Starts the bridge. Reads bridge.properties from this folder.
cd /d "%~dp0"
java -cp out geoplanph.RfidBridge
