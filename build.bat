@echo off
cd /d "%~dp0"
if not exist out mkdir out
javac -d out src\main\java\geoplanph\*.java
if errorlevel 1 (echo BUILD FAILED & exit /b 1)
echo Build OK  ^(classes in .\out^)
