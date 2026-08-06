@echo off
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
cd /d "C:\Users\catsk\auto_walking"
call gradlew.bat compileDebugKotlin > "C:\Users\catsk\auto_walking\build_log.txt" 2>&1
echo BUILD_EXIT_CODE:%ERRORLEVEL% >> "C:\Users\catsk\auto_walking\build_log.txt"
