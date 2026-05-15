# Run once to bootstrap the Gradle wrapper, so subsequent builds use ./gradlew.
# After this, you can open the `android` folder in Android Studio.
#
# Usage:  .\bootstrap-wrapper.ps1

$ErrorActionPreference = "Stop"

$gradleVer = "8.10.2"
$installRoot = "$env:USERPROFILE\.gradle\bootstrap"
$installPath = "$installRoot\gradle-$gradleVer"
$gradleBat = "$installPath\bin\gradle.bat"

if (-not (Test-Path $gradleBat)) {
    Write-Host "downloading Gradle $gradleVer to $installRoot ..."
    New-Item -ItemType Directory -Force -Path $installRoot | Out-Null
    $zip = "$installRoot\gradle.zip"
    Invoke-WebRequest `
        -Uri "https://services.gradle.org/distributions/gradle-$gradleVer-bin.zip" `
        -OutFile $zip `
        -UseBasicParsing
    Expand-Archive -Path $zip -DestinationPath $installRoot -Force
    Remove-Item $zip
}

Write-Host "running 'gradle wrapper' to generate gradlew/gradlew.bat ..."
& $gradleBat wrapper --gradle-version $gradleVer

Write-Host ""
Write-Host "OK. Now you can:"
Write-Host "  - open this directory in Android Studio  (Sync Gradle when prompted)"
Write-Host "  - or run:  .\gradlew.bat :protocol:test"
