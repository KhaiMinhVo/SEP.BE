param(
    [Parameter(Mandatory = $true)][string]$PlantUmlJar,
    [string]$JavaExecutable = 'java',
    [string]$DiagramDirectory = $PSScriptRoot
)
$ErrorActionPreference = 'Stop'
$jarPath = (Resolve-Path -LiteralPath $PlantUmlJar).Path
$diagramPaths = @(Get-ChildItem -LiteralPath $DiagramDirectory -Recurse -Filter '*.puml' | Sort-Object FullName | ForEach-Object { $_.FullName })
if ($diagramPaths.Count -eq 0) { throw 'No PlantUML sources found.' }
foreach ($format in @('svg', 'png')) {
    & $JavaExecutable '-Djava.awt.headless=true' '-DPLANTUML_LIMIT_SIZE=16000' '-jar' $jarPath '-charset' 'UTF-8' '-failfast2' "-t$format" '-o' 'rendered' @diagramPaths
    if ($LASTEXITCODE -ne 0) { throw "PlantUML $format render failed: $LASTEXITCODE" }
}
Write-Output "Rendered $($diagramPaths.Count) diagrams as SVG and PNG."
