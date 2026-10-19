# Windows version of restore.sh. Undo everything: re-enable every package in the debloat list, bring back
# the stock launcher (and Google TV's setup app) and make the stock launcher the default home again.
#   tools\restore.ps1 examples\vu-glo-google-tv\debloat.txt
#   tools\restore.ps1 examples\vu-glo-google-tv\debloat.txt -RemoveLauncher   also uninstall the tv-home launcher
#   $env:STOCK_LAUNCHER='com.google.android.tvlauncher/.MainActivity'; tools\restore.ps1 list.txt   (older Android TV)
param([Parameter(Mandatory)][string]$List, [switch]$RemoveLauncher)
$ErrorActionPreference = 'Stop'
$List = (Resolve-Path $List).Path
Set-Location (Join-Path $PSScriptRoot '..')
$PY = 'tools\venv\Scripts\python.exe'
$StockLauncher = if ($env:STOCK_LAUNCHER) { $env:STOCK_LAUNCHER } else { 'com.google.android.apps.tv.launcherx/.home.HomeActivity' }
$StockPkg = $StockLauncher.Split('/')[0]
$StockScreensaver = if ($env:STOCK_SCREENSAVER) { $env:STOCK_SCREENSAVER } else { 'com.google.android.apps.tv.dreamx/.service.Backdrop' }

$cmds = @(Get-Content $List | Where-Object { $_ -notmatch '^\s*(#|$)' } | ForEach-Object { "pm enable $(($_.Trim() -split '\s+')[0])" })
$cmds += "pm enable com.google.android.tungsten.setupwraith"
$cmds += "pm enable $StockPkg"
$cmds += "cmd package set-home-activity --user 0 $StockLauncher"
$cmds += "settings put secure screensaver_components $StockScreensaver"
if ($RemoveLauncher) { $cmds += "pm uninstall com.mihir.tvhome" }
$cmds += "am force-stop com.mihir.tvhome"
$cmds += "input keyevent KEYCODE_HOME"
$cmds | & $PY -I tools\tv.py --batch
Write-Host "Restored. Press Home on the remote."
