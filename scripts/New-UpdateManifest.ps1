param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][long]$VersionCode,
    [Parameter(Mandatory = $true)][string]$VersionName,
    [Parameter(Mandatory = $true)][string]$Notes,
    [Parameter(Mandatory = $true)][string]$ApkUrl,
    [Parameter(Mandatory = $true)][string]$OutputPath
)

$apk = Get-Item -LiteralPath $ApkPath -ErrorAction Stop
if ($apk.PSIsContainer -or -not $apk.Name.EndsWith('.apk', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'ApkPath 必须指向 APK 文件。'
}
if ($ApkUrl -notmatch '^https://') {
    throw 'ApkUrl 必须使用 HTTPS。'
}

$manifest = [ordered]@{
    packageName = 'com.messagestar.app'
    versionCode = $VersionCode
    versionName = $VersionName
    notes = $Notes
    apkUrl = $ApkUrl
    sizeBytes = $apk.Length
    sha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
$json = $manifest | ConvertTo-Json -Depth 3
[System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($OutputPath), $json, [System.Text.UTF8Encoding]::new($false))
Write-Output "已生成 $OutputPath，SHA-256: $($manifest.sha256)"
