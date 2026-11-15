# LADA Trip: publish ONLY the prepared source project as a NEW PUBLIC GitHub repository.
# Does not upload server .env, tokens, GPS data, private keys, binaries or history from other repos.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$repo = 'paradisemarketwb/LADA_Trip'

function Stop-IfFailed([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "Ошибка: $step. Exit code $LASTEXITCODE" }
}
function Get-Gh {
    $command = Get-Command 'gh' -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($candidate in @('C:\Program Files\GitHub CLI\gh.exe', "$env:LOCALAPPDATA\Programs\GitHub CLI\gh.exe")) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw 'Git не установлен. Установите Git for Windows с https://git-scm.com/download/win'
}
$gh = Get-Gh
if (-not $gh) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw 'Нужен GitHub CLI. Установите https://cli.github.com/ и повторите запуск.'
    }
    Write-Host 'GitHub CLI отсутствует. Будет запущена его установка через winget.' -ForegroundColor Yellow
    & winget install --id GitHub.cli --exact --source winget --accept-package-agreements --accept-source-agreements
    Stop-IfFailed 'установка GitHub CLI'
    $gh = Get-Gh
    if (-not $gh) {throw 'GitHub CLI установлен, но не найден. Закройте окно, откройте PowerShell заново и повторите запуск.'}
}
& $gh auth status 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'В браузере откроется вход в GitHub. Токены в код проекта не записываются.' -ForegroundColor Cyan
    & $gh auth login --web --git-protocol https
    Stop-IfFailed 'авторизация GitHub'
}
$login = (& $gh api user --jq '.login').Trim()
Stop-IfFailed 'чтение текущего GitHub аккаунта'
if ($login -ine 'paradisemarketwb') {
    throw "Авторизован другой аккаунт GitHub ($login). Нужен paradisemarketwb. Выполните 'gh auth switch' или войдите под нужным аккаунтом."
}
& $gh repo view $repo 1>$null 2>$null
if ($LASTEXITCODE -eq 0) {throw "Репозиторий $repo уже существует. Скрипт ничего не перезаписывает."}

if (Test-Path -LiteralPath '.git') { throw 'Папка уже содержит .git — создайте чистую распакованную копию архива.' }
$forbidden = @('.env','server/.env','server/data','identity.pk8','certificate.p7b','.github/secrets','*.keystore','*.jks')
foreach ($item in $forbidden) {
    if (Test-Path -LiteralPath $item) { throw "Найден потенциально конфиденциальный файл/путь: $item" }
}
# Only source files from this cleaned distribution are staged, no arbitrary files from other projects.
& git init -b main
Stop-IfFailed 'git init'
& git config --local user.name $login
Stop-IfFailed 'git user.name'
$userId = (& $gh api user --jq '.id').Trim()
Stop-IfFailed 'чтение GitHub ID'
& git config --local user.email "$userId+$login@users.noreply.github.com"
Stop-IfFailed 'git user.email'
& git add --all
Stop-IfFailed 'git add'
$staged = & git diff --cached --name-only
if ($staged -match '(^|/)(\.env$|identity\.pk8$|certificate\.p7b$|.*\.keystore$|.*\.jks$|server/data/)') {
    throw 'В индексе git обнаружены конфиденциальные данные. Публикация остановлена.'
}
& git commit -m 'feat: initial LADA Trip engineering v0.2 public source'
Stop-IfFailed 'git commit'
Write-Host 'Создаём новый ПУБЛИЧНЫЙ репозиторий. Каждый сможет читать код, но писать сможет только владелец.' -ForegroundColor Yellow
& $gh repo create $repo --public --source . --remote origin --push --disable-issues --disable-wiki --description 'LADA Trip Engineering: GPS trip logger and read-only vehicle diagnostics for Humax EnjoY Vision Pro'
Stop-IfFailed 'gh repo create --public --push'

# Disable discussions; no repository collaborators are added.
& $gh repo edit $repo --enable-discussions=false 2>$null
if ($LASTEXITCODE -ne 0) {Write-Warning 'Не удалось выключить Discussions, это можно сделать в Settings.'}

# Restrict updates to the main branch to repository admins (= the owner on a personal repository).
# Two rulesets: admin-only normal updates, and universal ban on deletion/force-push.
$rulesets = @(
    @{
        name='Only repository admins can update main'
        target='branch'; enforcement='active'
        bypass_actors=@(@{actor_id=5;actor_type='RepositoryRole';bypass_mode='always'})
        conditions=@{ref_name=@{include=@('refs/heads/main');exclude=@()}}
        rules=@(@{type='update'})
    },
    @{
        name='Protect main from deletion and force pushes'
        target='branch'; enforcement='active'
        bypass_actors=@()
        conditions=@{ref_name=@{include=@('refs/heads/main');exclude=@()}}
        rules=@(@{type='deletion'},@{type='non_fast_forward'})
    }
)
foreach ($ruleset in $rulesets) {
    $temp = Join-Path $env:TEMP ([guid]::NewGuid().ToString()+'.json')
    try {
        $ruleset | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $temp -Encoding UTF8
        & $gh api -X POST "repos/$repo/rulesets" --input $temp 1>$null
        if ($LASTEXITCODE -ne 0) {Write-Warning "Не удалось применить защиту $($ruleset.name). Сделайте это вручную в Settings > Rules > Rulesets."}
        else {Write-Host "Защита включена: $($ruleset.name)" -ForegroundColor Green}
    } finally {Remove-Item -LiteralPath $temp -ErrorAction SilentlyContinue}
}

Write-Host ''
Write-Host "Репозиторий создан: https://github.com/$repo" -ForegroundColor Green
Write-Host "Сборка APK: https://github.com/$repo/actions" -ForegroundColor Cyan
Write-Host 'Если Actions не запускаются автоматически, откройте Actions > Build Android debug APK > Run workflow.'
Write-Host 'Проверьте Settings > Collaborators: вы никому не предоставили право записи.'
Write-Host 'Токены Telegram и координаты должны оставаться только в вашей личной среде.'
