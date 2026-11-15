@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
echo Создание ПУБЛИЧНОГО репозитория LADA Trip в личном GitHub.
echo Только ваш аккаунт получит право записи; публичный код сможет читать любой.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\publish-public.ps1"
echo.
if errorlevel 1 (
  echo Сборка репозитория не завершена. Прочтите сообщение об ошибке выше.
) else (
  echo Операция завершена. Проверьте ссылку GitHub выше.
)
pause
