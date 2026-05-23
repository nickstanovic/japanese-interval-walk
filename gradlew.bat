@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "CACHED_GRADLE=%USERPROFILE%\.gradle\wrapper\dists\gradle-8.13-bin"

if exist "%CACHED_GRADLE%" (
  for /d %%D in ("%CACHED_GRADLE%\*") do (
    if exist "%%D\gradle-8.13\bin\gradle.bat" (
      call "%%D\gradle-8.13\bin\gradle.bat" %*
      exit /b %ERRORLEVEL%
    )
  )
)

where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  gradle %*
  exit /b %ERRORLEVEL%
)

echo Gradle was not found. Open this folder in Android Studio, or install Gradle and rerun this command.
exit /b 1
