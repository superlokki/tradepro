# Compile Trade Pro contre l'API de l'installation Bookmap locale (pas besoin de Gradle).
# Usage : powershell -ExecutionPolicy Bypass -File build.ps1 [-Name tradepro-v2.jar] [-BookmapLib 'D:\Bookmap\lib']
param(
    [string]$Name = 'tradepro.jar',                           # autre nom si Bookmap verrouille le jar chargé
    [string]$BookmapLib = 'C:\Program Files\Bookmap\lib'        # dossier lib de l'installation Bookmap
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$cp = "$BookmapLib\bm-l1api.jar;$BookmapLib\bm-simplified-api-wrapper.jar"
$out = Join-Path $root 'build\classes'

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw 'javac introuvable : installe un JDK (17 ou plus) et rouvre le terminal.'
}
if (-not (Test-Path "$BookmapLib\bm-l1api.jar")) {
    throw "API Bookmap introuvable dans $BookmapLib : indique le bon dossier avec -BookmapLib."
}

# On retire les anciennes classes sans s'arrêter si Bookmap en tient une ouverte (rechargement rapide) :
# javac réécrit de toute façon celles qui existent encore dans les sources.
New-Item -ItemType Directory -Force $out | Out-Null
Get-ChildItem $out -Recurse -Filter *.class | ForEach-Object {
    try { Remove-Item -LiteralPath $_.FullName -Force -ErrorAction Stop } catch { }
}

$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
# Java 17, comme les exemples officiels : Bookmap repère l'add-on en scannant le bytecode, et son scanner (ASM)
# ne lit pas les versions trop récentes ("No entry points found in jar").
javac --release 17 -encoding UTF-8 -cp $cp -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation échouée.' }

# Rechargement rapide : charger ce fichier vide dans Bookmap lui fait lire les classes directement dans build\classes,
# donc plus besoin de changer le nom du jar à chaque essai.
$marker = Join-Path $out 'bm-strategy-package-fs-root.jar'
New-Item -ItemType File -Force $marker | Out-Null

# L'installeur Oracle n'expose que java/javac dans le PATH : on retrouve jar.exe via java.home
$jar = (Get-Command jar -ErrorAction SilentlyContinue).Source
if (-not $jar) {
    $javaHome = (cmd /c 'java -XshowSettings:properties -version 2>&1' | Select-String 'java\.home = (.+)$').Matches[0].Groups[1].Value.Trim()
    $jar = Join-Path $javaHome 'bin\jar.exe'
}
if (-not (Test-Path $jar)) { throw "jar.exe introuvable ($jar)." }
& $jar --create --file (Join-Path $root $Name) -C $out com
if ($LASTEXITCODE -ne 0) { throw 'Création du jar échouée.' }

Write-Host "OK -> $(Join-Path $root $Name)"
Write-Host "Rechargement rapide -> $marker"
