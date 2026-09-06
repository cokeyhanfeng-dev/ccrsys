# Windows PowerShell 5.1+ 打包入口；UTF-8 BOM 保证旧版 PowerShell 正确读取中文。
[CmdletBinding()]
param(
    [ValidateSet('1', '2', '12')][string]$Mode,
    [switch]$CheckOnly,
    [switch]$SkipTests
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$oldJava = $env:JAVA_HOME
$oldPath = $env:PATH
$stage = $null
$partial = $null
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Run-Native([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed (exit $LASTEXITCODE)." }
}
function Write-Lf([string]$Path, [string]$Content) {
    [System.IO.File]::WriteAllText($Path, ($Content -replace "`r`n", "`n"), $utf8)
}
try {
    if (!$Mode) { $Mode = Read-Host '请选择打包内容：1=后端，2=前端，12=全部' }
    if ($Mode -notin @('1', '2', '12')) { throw '请输入 1、2 或 12。' }
    $tar = (Get-Command tar.exe -ErrorAction Stop).Source
    $git = (Get-Command git.exe -ErrorAction Stop).Source
    if ($Mode -in @('1', '12')) {
        # 仅修改当前进程环境；不安装工具、不修改全局配置。
        $jdk = Join-Path $root '.tools\jdk-17'
        if ($env:CCR_JAVA_HOME) { $jdk = $env:CCR_JAVA_HOME }
        $java = Join-Path $jdk 'bin\java.exe'
        if (!(Test-Path -LiteralPath $java -PathType Leaf)) {
            throw 'Extract Windows JDK 17 into .tools\jdk-17, or set CCR_JAVA_HOME for this session.'
        }
        $javaVersion = & $java --version
        if ($LASTEXITCODE -ne 0 -or ($javaVersion -join ' ') -notmatch '(?:openjdk|java) 17[. ]') {
            throw 'JDK 17 is required.'
        }
        $env:JAVA_HOME = $jdk
        $env:PATH = (Join-Path $jdk 'bin') + ';' + $oldPath
        $mvn = Join-Path $root '.tools\maven\bin\mvn.cmd'
        if (!(Test-Path -LiteralPath $mvn)) { $mvn = (Get-Command mvn.cmd -ErrorAction Stop).Source }
        $mvnVersion = & $mvn -version
        if ($LASTEXITCODE -ne 0 -or ($mvnVersion -join ' ') -notmatch 'Apache Maven 3\.9\.') {
            throw 'Maven 3.9.x is required (.tools\maven or existing mvn.cmd).'
        }
    }
    if ($Mode -in @('2', '12')) {
        $node = (Get-Command node.exe -ErrorAction Stop).Source
        $npm = (Get-Command npm.cmd -ErrorAction Stop).Source
        $nodeVersion = & $node --version
        if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 20) {
            throw 'Node.js 20+ is required.'
        }
    }
    Write-Host '工具检查通过，未修改全局配置。'
    if ($CheckOnly) { return }

    if ($Mode -in @('1', '12')) {
        $mavenArgs = @('-B', '-f', (Join-Path $root 'backend\pom.xml'),
            "-Dmaven.repo.local=$(Join-Path $root '.cache\m2\repository')",
            '-pl', 'ccr-admin', '-am', 'clean', 'package')
        if ($SkipTests) {
            Write-Warning '按显式参数跳过后端测试；此次打包不能视为测试验证通过。'
            $mavenArgs += '-DskipTests'
        }
        Run-Native $mvn $mavenArgs
        $jar = Join-Path $root 'backend\ccr-admin\target\ccr-admin.jar'
        if (!(Test-Path -LiteralPath $jar) -or (Get-Item -LiteralPath $jar).Length -eq 0) {
            throw 'Missing or empty ccr-admin.jar.'
        }
    }
    if ($Mode -in @('2', '12')) {
        Push-Location (Join-Path $root 'frontend')
        try {
            Run-Native $npm @('ci', '--cache', (Join-Path $root '.cache\npm'))
            Run-Native $npm @('run', 'build', '--cache', (Join-Path $root '.cache\npm'))
        } finally { Pop-Location }
        if (!(Test-Path -LiteralPath (Join-Path $root 'frontend\dist\index.html'))) {
            throw 'Missing frontend/dist/index.html.'
        }
    }
    $label = @{ '1' = 'backend'; '2' = 'frontend'; '12' = 'full' }[$Mode]
    $buildTime = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $name = "ccr-release-$buildTime-$label"
    $release = Join-Path $root 'release'
    $stage = Join-Path ([System.IO.Path]::GetTempPath()) ('ccr-release-' + [guid]::NewGuid().ToString('N'))
    $bundle = Join-Path $stage $name
    foreach ($dir in @($release, (Join-Path $bundle 'backend'), (Join-Path $bundle 'frontend'))) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    if ($Mode -in @('1', '12')) { Copy-Item -LiteralPath $jar -Destination (Join-Path $bundle 'backend\ccr-admin.jar') }
    if ($Mode -in @('2', '12')) {
        Copy-Item -LiteralPath (Join-Path $root 'frontend\dist') -Destination (Join-Path $bundle 'frontend\dist') -Recurse
    }
    # Git for Windows 可能检出 CRLF；交付服务器的脚本统一为 UTF-8 无 BOM/LF。
    $deploy = [System.IO.File]::ReadAllText((Join-Path $root 'scripts\deploy-release.sh'))
    Write-Lf (Join-Path $bundle 'deploy-release.sh') $deploy
    $commit = & $git -C $root rev-parse --short HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read Git commit.' }
    $dirty = & $git -C $root status --porcelain --untracked-files=no
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read Git status.' }
    Write-Lf (Join-Path $bundle 'MANIFEST') "package=$name`nmode=$Mode`nbuild_time=$buildTime`ngit_commit=$commit`ntracked_dirty=$([bool]$dirty)`n"
    $hashes = foreach ($file in (Get-ChildItem -LiteralPath (Join-Path $bundle 'backend'), (Join-Path $bundle 'frontend') -Recurse -File | Sort-Object FullName)) {
        $relative = $file.FullName.Substring($bundle.Length + 1).Replace('\', '/')
        (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $relative
    }
    Write-Lf (Join-Path $bundle 'SHA256SUMS') (($hashes -join "`n") + "`n")
    $archive = Join-Path $release "$name.tar.gz"
    $partial = "$archive.partial"
    Run-Native $tar @('-czf', $partial, '-C', $stage, $name)
    Run-Native $tar @('-tzf', $partial)
    Move-Item -LiteralPath $partial -Destination $archive
    $partial = $null
    Write-Lf (Join-Path $release 'deploy-release.sh') $deploy
    Write-Host "发布包已生成：$archive"
    Write-Host "上传发布包及 release\deploy-release.sh 到 /tmp 后执行：bash /tmp/deploy-release.sh $Mode"
} catch {
    Write-Error $_ -ErrorAction Continue
    exit 1
} finally {
    $env:JAVA_HOME = $oldJava
    $env:PATH = $oldPath
    if ($partial -and (Test-Path -LiteralPath $partial)) { Remove-Item -LiteralPath $partial -Force }
    if ($stage -and (Test-Path -LiteralPath $stage)) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
