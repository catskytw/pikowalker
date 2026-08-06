@echo off
chcp 65001 >nul
title PikoWalker 自動編譯安裝
color 0A

echo ============================================
echo   PikoWalker - 自動編譯並安裝到手機
echo ============================================
echo.

:: 設定環境
set "ANDROID_SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%ANDROID_SDK%\platform-tools\adb.exe"
set "PROJECT_DIR=%~dp0"

:: 找 Java
where java >nul 2>&1
if %errorlevel%==0 (
    echo [OK] 找到 Java
) else (
    echo [錯誤] 找不到 Java，請確認 JDK 17 已安裝
    pause
    exit /b 1
)

:: 找 Android SDK
if exist "%ADB%" (
    echo [OK] 找到 ADB: %ADB%
) else (
    echo [錯誤] 找不到 ADB，請確認 Android SDK 已安裝
    pause
    exit /b 1
)

echo.
echo === 開始編譯 APK（約 3-5 分鐘）===
echo.

cd /d "%PROJECT_DIR%"
set "JAVA_HOME="

:: 自動找 Java Home
for /f "tokens=*" %%i in ('where java') do (
    set "JAVA_EXE=%%i"
    goto :found_java
)
:found_java
for %%i in ("%JAVA_EXE%") do set "JAVA_BIN=%%~dpi"
set "JAVA_HOME=%JAVA_BIN%.."

set "ANDROID_HOME=%ANDROID_SDK%"

call gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo.
    echo [失敗] 編譯出錯！請看上方訊息
    pause
    exit /b 1
)

echo.
echo [成功] APK 編譯完成！
echo.

:: 找 APK
set "APK=%PROJECT_DIR%app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    echo [錯誤] 找不到 APK 檔案: %APK%
    pause
    exit /b 1
)

echo === 等待手機連線 ===
echo 請確認手機已用 USB 連接並開啟 USB 偵錯模式
echo.

:wait_device
"%ADB%" devices 2>nul | findstr /v "List" | findstr /v "^$" >nul
if %errorlevel% neq 0 (
    echo 等待手機中...（每 3 秒檢查一次，按 Ctrl+C 可取消）
    timeout /t 3 /nobreak >nul
    goto :wait_device
)

echo.
echo [OK] 手機已連線！
echo.
echo === 安裝 PikoWalker ===

"%ADB%" install -r "%APK%"
if %errorlevel%==0 (
    echo.
    echo ============================================
    echo   安裝成功！請在手機上打開 PikoWalker
    echo ============================================
) else (
    echo.
    echo [失敗] 安裝失敗，請看上方訊息
)

echo.
pause
