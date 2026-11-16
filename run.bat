@echo off
rem Usage: run.bat                  → GUI
rem        run.bat ^<project-dir^> [output-dir]
if "%~1"=="" (
  java -jar "%~dp0dist\jakartaee-analyzer.jar" --gui
  goto :eof
)
set OUT=%~2
if "%OUT%"=="" set OUT=analysis-output
java -jar "%~dp0dist\jakartaee-analyzer.jar" "%~1" -o "%OUT%"
