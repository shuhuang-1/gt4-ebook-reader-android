$env:JAVA_HOME = "C:\Android\jdk"
$env:PATH = "$env:JAVA_HOME\bin;C:\Android\gradle\bin;$env:PATH"
Set-Location "E:\cpp_code\AndroidReader"
.\gradlew.bat clean assembleDebug

if ($LASTEXITCODE -eq 0) {
    Write-Host "`n成功! APK: E:\cpp_code\AndroidReader\app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
} else {
    Write-Host "`n编译失败，看上面的红色报错" -ForegroundColor Red
}
Read-Host "按回车关闭"