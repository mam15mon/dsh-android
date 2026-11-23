# 从已提交的源码生成可构建的发布归档。历史源码快照单独保存在 Git 中。
param([string]$Revision = 'HEAD')

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $root 'build/source-release'
$stage = Join-Path $output 'dsh-phone-source'
$zip = Join-Path $output 'dsh-phone-source.zip'

$commit = git -C $root rev-parse --verify "${Revision}^{commit}"
if ($LASTEXITCODE -ne 0) { throw '无法解析源码版本' }
foreach ($directory in @((Join-Path $root 'build'), $output)) {
    if ((Test-Path -LiteralPath $directory) -and (Get-Item -LiteralPath $directory).LinkType) {
        throw "输出目录不能是链接：$directory"
    }
}
$files = git -C $root ls-tree -r --name-only $commit
if ($LASTEXITCODE -ne 0) { throw '读取源码清单失败' }
if ($files -match '(?i)(^|/)(keystore\.properties|[^/]+\.(jks|keystore))$') {
    throw 'Git 版本中包含签名文件，已中止'
}
New-Item -ItemType Directory -Path $output -Force | Out-Null
git -C $root archive --format=zip --output=$zip $commit -- . ':(exclude)DSH-Mobile-Android-源码'
if ($LASTEXITCODE -ne 0) { throw '生成源码 ZIP 失败' }

if (Test-Path -LiteralPath $stage) {
    $resolved = (Resolve-Path -LiteralPath $stage).Path
    $expected = [IO.Path]::GetFullPath($stage)
    if ($resolved -ne $expected -or (Get-Item -LiteralPath $stage).LinkType) {
        throw "拒绝替换意外的输出目录：$resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
Expand-Archive -LiteralPath $zip -DestinationPath $stage
$hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $([IO.Path]::GetFileName($zip))" | Set-Content -LiteralPath "$zip.sha256" -Encoding ascii
Get-Item -LiteralPath $zip, "$zip.sha256"
