@echo off
rem Fake reader for testing without hardware. Run this in a 2nd window while run.bat is up.
cd /d "%~dp0"
java -cp out geoplanph.TagSimulator 127.0.0.1 20059
