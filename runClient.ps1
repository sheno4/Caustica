param(
	[ValidateSet("fabric", "neoforge")]
	[string]$Loader = "fabric"
)

$ErrorActionPreference = "Stop"

$candelaRoot = $PSScriptRoot

Push-Location $candelaRoot
try {
	# Heap/GC flags and the client program arguments live on the Gradle run configuration
	# (CLIENT_JVM_ARGUMENTS / CLIENT_PROGRAM_ARGUMENTS). JAVA_TOOL_OPTIONS would leak them into every
	# forked JVM, and NeoForge's JDK 21 tooling JVM rejects them; --args is unusable because NeoForge's
	# devlaunch reads the first program argument as the main class.
	.\gradlew.bat --no-daemon ("-Ploader={0}" -f $Loader) :runClient
} finally {
	Pop-Location
}
