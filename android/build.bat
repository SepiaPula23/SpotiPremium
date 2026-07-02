@echo off
REM ========================================
REM  Potify Android APK Builder
REM  Requires: Java 17+ and Android SDK
REM ========================================
setlocal enabledelayedexpansion

echo [1/4] Checking prerequisites...

where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo ERROR: Java not found. Install JDK 17+ from https://adoptium.net
    exit /b 1
)

if "%ANDROID_HOME%"=="" (
    if exist "%USERPROFILE%\AppData\Local\Android\Sdk" (
        set ANDROID_HOME=%USERPROFILE%\AppData\Local\Android\Sdk
    ) else (
        echo Android SDK not found.
        echo Download command-line tools from:
        echo https://developer.android.com/studio#command-line-tools-only
        echo Extract to %USERPROFILE%\Android\Sdk
        echo Then set: setx ANDROID_HOME "%%USERPROFILE%%\Android\Sdk"
        exit /b 1
    )
)

echo    ANDROID_HOME=%ANDROID_HOME%

echo [2/4] Setting up Gradle wrapper...
if not exist "%~dp0gradle\wrapper\gradle-wrapper.jar" (
    echo Downloading Gradle wrapper...
    powershell -Command "& {Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile '%TEMP%\gradle.zip'}"
    powershell -Command "& {Expand-Archive -Path '%TEMP%\gradle.zip' -DestinationPath '%TEMP%\gradle-' -Force}"
    "%TEMP%\gradle-\gradle-8.9\bin\gradle.bat" wrapper --gradle-version 8.9
)

echo [3/4] Installing SDK components (if needed)...
call "%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager" "platforms;android-35" "build-tools;35.0.0" 2>nul

echo [4/4] Building APK...
call gradlew assembleDebug

if %ERRORLEVEL% equ 0 (
    echo.
    echo ======== BUILD SUCCESS ========
    echo APK: %~dp0app\build\outputs\apk\debug\app-debug.apk
    echo Install with: gradlew installDebug
    echo ===============================
) else (
    echo ======== BUILD FAILED ========
    echo Check errors above.
)
pause
