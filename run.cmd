@echo off
setlocal
cd /d "%~dp0"
chcp 65001 >nul
set "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1"
set "MAVEN_OPTS=%MAVEN_OPTS% -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
call "%~dp0mvnw.cmd" -q spring-boot:run
set "APP_EXIT=%ERRORLEVEL%"
if not "%APP_EXIT%"=="0" echo Program failed. Read the error above.
pause
exit /b %APP_EXIT%
