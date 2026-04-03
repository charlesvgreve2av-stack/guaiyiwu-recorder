# 生成测试签名密钥的 PowerShell 脚本
# 需要安装 Java JDK

$keystoreFile = "release.keystore"
$alias = "guaiyiwu"
$password = "guaiyiwu123"
$validity = 36500  # 100年

Write-Host "正在生成测试签名密钥..." -ForegroundColor Green
Write-Host "密钥库文件: $keystoreFile" -ForegroundColor Yellow
Write-Host "密钥别名: $alias" -ForegroundColor Yellow
Write-Host "密钥密码: $password" -ForegroundColor Yellow
Write-Host ""

# 检查 keytool 是否存在
$keytool = & where.exe keytool 2>$null
if (-not $keytool) {
    # 尝试常见路径
    $possiblePaths = @(
        "C:\Program Files\Java\jdk-17\bin\keytool.exe",
        "C:\Program Files\Java\jdk-11\bin\keytool.exe",
        "C:\Program Files\Java\jdk-1.8\bin\keytool.exe",
        "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe",
        "C:\Program Files\Android\Android Studio\jre\bin\keytool.exe"
    )
    
    foreach ($path in $possiblePaths) {
        if (Test-Path $path) {
            $keytool = $path
            break
        }
    }
}

if (-not $keytool) {
    Write-Host "错误: 找不到 keytool.exe" -ForegroundColor Red
    Write-Host "请确保已安装 Java JDK 或 Android Studio" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "你可以手动下载 JDK: https://adoptium.net/" -ForegroundColor Cyan
    exit 1
}

Write-Host "找到 keytool: $keytool" -ForegroundColor Green

# 生成密钥库
& $keytool -genkey -v `
    -keystore $keystoreFile `
    -alias $alias `
    -keyalg RSA `
    -keysize 2048 `
    -validity $validity `
    -storepass $password `
    -keypass $password `
    -dname "CN=Guaiyiwu, OU=Test, O=Test, L=Beijing, ST=Beijing, C=CN"

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "✅ 签名密钥生成成功!" -ForegroundColor Green
    Write-Host ""
    Write-Host "文件位置: $(Resolve-Path $keystoreFile)" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "=== GitHub Secrets 配置信息 ===" -ForegroundColor Cyan
    Write-Host ""
    
    # 生成 Base64
    $base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path $keystoreFile)))
    
    Write-Host "KEYSTORE_BASE64:" -ForegroundColor Yellow
    Write-Host $base64 -ForegroundColor Gray
    Write-Host ""
    Write-Host "KEYSTORE_PASSWORD: $password" -ForegroundColor Yellow
    Write-Host "KEY_ALIAS: $alias" -ForegroundColor Yellow
    Write-Host "KEY_PASSWORD: $password" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "请将以上信息添加到 GitHub Secrets 中" -ForegroundColor Green
    
    # 保存到文件方便复制
    $secretsInfo = @"
GitHub Secrets 配置:

KEYSTORE_BASE64:
$base64

KEYSTORE_PASSWORD: $password
KEY_ALIAS: $alias
KEY_PASSWORD: $password
"@
    $secretsInfo | Out-File -FilePath "github-secrets.txt" -Encoding UTF8
    Write-Host ""
    Write-Host "配置信息已保存到 github-secrets.txt 文件" -ForegroundColor Green
} else {
    Write-Host "❌ 生成失败" -ForegroundColor Red
}
