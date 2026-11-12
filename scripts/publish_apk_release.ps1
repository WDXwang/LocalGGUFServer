# 把构建出的 APK 发成 GitHub Release（附件不进 git 历史）
#
# 用法（在仓库根目录）：
#   $env:GITHUB_TOKEN = "<有 repo 权限的 PAT>"
#   .\scripts\publish_apk_release.ps1 -Version 1.0.5 -ApkPath "app\build\outputs\apk\debug\app-debug.apk" -Notes "改动摘要"
#
# 只负责：建 tag + 建 Release + 上传 APK 附件。
# 仓库 releases/ 目录的"只留最新版"需要按 releases/README.md 手动同步，脚本不动 git。

param(
    [Parameter(Mandatory = $true)][string]$Version,          # 例如 1.0.5
    [Parameter(Mandatory = $true)][string]$ApkPath,          # APK 路径
    [string]$Notes = "",                                     # Release 说明
    [string]$RepoSlug = "WDXwang/LocalGGUFServer",
    [string]$TargetCommitish = "master",
    [string]$AssetName = ""
)

$ErrorActionPreference = "Stop"

if (-not $env:GITHUB_TOKEN) {
    throw "未设置 GITHUB_TOKEN（需要 repo 权限的 Personal Access Token）"
}
if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK 不存在: $ApkPath"
}

$token = $env:GITHUB_TOKEN
$tag = "v$Version"
$apk = (Resolve-Path -LiteralPath $ApkPath).Path
if (-not $AssetName) { $AssetName = Split-Path -Leaf $apk }
$sizeMb = [math]::Round((Get-Item -LiteralPath $apk).Length / 1MB, 2)

# curl.exe 在 Windows 10+ 自带；--ssl-no-revoke 绕过部分网络环境下 schannel 的吊销检查失败
function Invoke-GitHubApi {
    param([string]$Method, [string]$Url, [string]$BodyFile, [string]$ContentType = "application/json")
    if ($BodyFile) {
        $raw = & curl.exe -sS --ssl-no-revoke -X $Method `
            -H "Authorization: token $token" -H "User-Agent: publish-bot" `
            -H "Accept: application/vnd.github+json" -H "Content-Type: $ContentType" `
            --data-binary "@$BodyFile" $Url
    } else {
        $raw = & curl.exe -sS --ssl-no-revoke -X $Method `
            -H "Authorization: token $token" -H "User-Agent: publish-bot" `
            -H "Accept: application/vnd.github+json" $Url
    }
    return $raw
}

# 1. 取完整 commit sha：短 sha 会被 API 判为非法 target_commitish
$sha = (& git rev-parse $TargetCommitish).Trim()

# 2. 建 Release
$body = @{ tag_name = $tag; target_commitish = $sha; name = $Version; body = $Notes } | ConvertTo-Json -Depth 3
$tmp = Join-Path $env:TEMP "release_$Version.json"
[IO.File]::WriteAllText($tmp, $body, (New-Object Text.UTF8Encoding $false))
$res = Invoke-GitHubApi -Method POST -Url "https://api.github.com/repos/$RepoSlug/releases" -BodyFile $tmp
$json = $res | ConvertFrom-Json
if (-not $json.id) { throw "创建 Release 失败：$res" }

Write-Host "Release 已创建: $($json.html_url) (id=$($json.id))"

# 3. 上传 APK 附件
$up = & curl.exe -sS --ssl-no-revoke -X POST `
    -H "Authorization: token $token" -H "User-Agent: publish-bot" `
    -H "Content-Type: application/vnd.android.package-archive" `
    --data-binary "@$apk" `
    "https://uploads.github.com/repos/$RepoSlug/releases/$($json.id)/assets?name=$AssetName"
$asset = $up | ConvertFrom-Json
if (-not $asset.browser_download_url) { throw "上传附件失败：$up" }

Write-Host "附件已上传: $($asset.browser_download_url) ($sizeMb MB)"
Write-Host ""
Write-Host "接下来手动同步仓库里的最新版（历史版本已在 Release 留档）："
Write-Host "  git rm releases/<旧版>.apk"
Write-Host "  Copy-Item '$apk' releases\LocalGGUFServer-$Version-debug-D9300.apk"
Write-Host "  git add releases; git commit -m 'Release $Version'; git push"
