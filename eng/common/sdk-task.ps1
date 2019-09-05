[CmdletBinding(PositionalBinding=$false)]
Param(
  [string] $configuration = "Debug",
  [string] $task,
  [string] $verbosity = "minimal",
  [string] $msbuildEngine = $null,
  [string] $binlogArtifactName = $null,
  [string] $logsAzdoContainer = $null,
  [switch] $restore,
  [switch] $prepareMachine,
  [switch] $help,
  [Parameter(ValueFromRemainingArguments=$true)][String[]]$properties
)

$ci = $true
$binaryLog = $true
$warnAsError = $true

. $PSScriptRoot\tools.ps1

function Print-Usage() {
  Write-Host "Common settings:"
  Write-Host "  -task <value>           Name of Arcade task (name of a project in SdkTasks directory of the Arcade SDK package)"
  Write-Host "  -restore                Restore dependencies"
  Write-Host "  -verbosity <value>      Msbuild verbosity: q[uiet], m[inimal], n[ormal], d[etailed], and diag[nostic]"
  Write-Host "  -help                   Print help and exit"
  Write-Host ""

  Write-Host "Advanced settings:"
  Write-Host "  -prepareMachine         Prepare machine for CI run"
  Write-Host "  -msbuildEngine <value>  Msbuild engine to use to run build ('dotnet', 'vs', or unspecified)."
  Write-Host ""
  Write-Host "Command line arguments not listed above are passed thru to msbuild."
}

function Build([string]$target, [string]$logsAzdoContainer, [string]$binlogArtifactName) {
  $logSuffix = if ($target -eq "Execute") { "" } else { ".$target" }
  $log = Join-Path $LogDir "$task$logSuffix.binlog"
  $outputPath = Join-Path $ToolsetDir "$task\\"

  try {
    MSBuild $taskProject `
      /bl:$log `
      /t:$target `
      /p:Configuration=$configuration `
      /p:RepoRoot=$RepoRoot `
      /p:BaseIntermediateOutputPath=$outputPath `
      @properties
  }
  finally {
    if (($null -ne $binlogArtifactName -and $binlogArtifactName -ne "") -and ($null -ne $logsAzdoContainer -and $logsAzdoContainer -ne "")) {
      $finalArtifactName = "$task$logSuffix-$binlogArtifactName.binlog"
      Write-PipelinePublishArtifact -ArtifactSourcePath $log -TargetContainer $logsAzdoContainer -TargetArtifactName $finalArtifactName
    }
  }
}

try {
  if ($help -or (($null -ne $properties) -and ($properties.Contains("/help") -or $properties.Contains("/?")))) {
    Print-Usage
    exit 0
  }

  if ($task -eq "") {
    Write-Host "Missing required parameter '-task <value>'" -ForegroundColor Red
    Print-Usage
    ExitWithExitCode 1
  }

  $taskProject = GetSdkTaskProject $task
  if (!(Test-Path $taskProject)) {
    Write-Host "Unknown task: $task" -ForegroundColor Red
    ExitWithExitCode 1
  }

  if ($restore) {
    Build "Restore" $logsAzdoContainer $binlogArtifactName
  }

  Build "Execute" $logsAzdoContainer $binlogArtifactName
}
catch {
  Write-Host $_
  Write-Host $_.Exception
  Write-Host $_.ScriptStackTrace
  ExitWithExitCode 1
}

ExitWithExitCode 0
