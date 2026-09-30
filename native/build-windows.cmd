@echo off
rem snqx.dll (x64, 정적 CRT) 과 점검 프로그램 snqx_cli.exe 를 빌드한다. Visual Studio 2022 (C++ 데스크톱 개발) 필요.
setlocal
set "CMAKE=%ProgramFiles%\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
if not exist "%CMAKE%" set "CMAKE=cmake"
"%CMAKE%" -S "%~dp0." -B "%~dp0build\windows-x64" -G "Visual Studio 17 2022" -A x64 || exit /b 1
"%CMAKE%" --build "%~dp0build\windows-x64" --config Release || exit /b 1
echo.
echo %~dp0build\windows-x64\Release\snqx.dll
