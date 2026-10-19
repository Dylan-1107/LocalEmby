# 本地影院 Emby 模拟服务器 - 手动 APK 构建脚本 (无需 Gradle)
# 用法: pwsh -File build.ps1
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$tools = Join-Path $root "tools"
$env:JAVA_HOME = Join-Path $tools "jdk-17.0.20.1+1"
$javaBin = Join-Path $env:JAVA_HOME "bin"
$bt = Join-Path $tools "android-14"          # build-tools 34 (zip 内目录名)
$platformJar = Join-Path $tools "android-34\android.jar"
$srcDir = Join-Path $root "app\src"
$resDir = Join-Path $srcDir "res"
$assetsDir = Join-Path $root "app\assets"
$manifest = Join-Path $srcDir "AndroidManifest.xml"
$build = Join-Path $root "build"
$outApk = Join-Path $root "LocalEmby-1.0.0.apk"

# 清理
Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path "$build\gen","$build\classes","$build\dex" -Force | Out-Null

Write-Host "[1/7] aapt2 compile resources..."
& "$bt\aapt2.exe" compile --dir $resDir -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/7] aapt2 link..."
& "$bt\aapt2.exe" link -o "$build\unsigned.apk" `
  -I $platformJar `
  --manifest $manifest `
  -A $assetsDir `
  --java "$build\gen" `
  --min-sdk-version 24 --target-sdk-version 34 `
  --auto-add-overlay `
  "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/7] javac..."
$sources = Get-ChildItem -Path "$srcDir\com","$build\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& "$javaBin\javac.exe" -encoding UTF-8 --release 8 `
  -classpath $platformJar `
  -d "$build\classes" `
  $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/7] jar classes + d8 dex..."
& "$javaBin\jar.exe" cf "$build\classes.jar" -C "$build\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& "$bt\d8.bat" --release --lib $platformJar --min-api 24 --output "$build\dex" "$build\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/7] add classes.dex into apk..."
Push-Location "$build\dex"
& "$javaBin\jar.exe" uf0 "$build\unsigned.apk" classes.dex
if ($LASTEXITCODE -ne 0) { throw "add dex failed" }
Pop-Location

# aapt2 在 Windows 上会把 assets 条目名写成反斜杠，修正为正斜杠（保留原压缩方式，resources.arsc 必须保持未压缩）
$py = "C:\Users\11829\.workbuddy-ai\binaries\python\versions\3.13.12\python.exe"
& $py (Join-Path $root "fix_asset_paths.py") "$build\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "asset path fix failed" }

Write-Host "[6/7] zipalign..."
& "$bt\zipalign.exe" -f 4 "$build\unsigned.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[7/7] sign..."
$ks = Join-Path $root "debug.keystore"
if (-not (Test-Path $ks)) {
  & "$javaBin\keytool.exe" -genkeypair -keystore $ks -alias androiddebugkey `
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
    -dname "CN=LocalEmby,O=Local,C=CN"
  if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
  --out $outApk "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "DONE -> $outApk"
& "$bt\apksigner.bat" verify --print-certs $outApk
