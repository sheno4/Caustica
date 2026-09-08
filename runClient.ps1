param(
	[ValidateSet("fabric", "neoforge")]
	[string]$Loader = "fabric"
)

$ErrorActionPreference = "Stop"

Push-Location $PSScriptRoot
try {
	# The Gradle run configuration keeps JVM settings scoped to the development client.
	.\gradlew.bat --no-daemon ("-Ploader={0}" -f $Loader) :runClient
	exit $LASTEXITCODE
} finally {
	Pop-Location
}
