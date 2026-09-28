param(
    [Parameter(Mandatory = $true)]
    [string] $Path
)

$resolved = (Resolve-Path -LiteralPath $Path).Path
$source = [IO.File]::ReadAllBytes($resolved)

if ($source.Length -lt 20 -or [Text.Encoding]::ASCII.GetString($source, 0, 4) -ne 'glTF') {
    throw "Not a binary glTF/VRM file: $resolved"
}

$jsonLength = [BitConverter]::ToInt32($source, 12)
$jsonType = [BitConverter]::ToUInt32($source, 16)
if ($jsonType -ne 0x4E4F534A) {
    throw "The first GLB chunk is not JSON: $resolved"
}

$json = [Text.Encoding]::UTF8.GetString($source, 20, $jsonLength).TrimEnd([char]0, [char]32)
$escapedCount = ([regex]::Matches($json, 'image\\/png')).Count
if ($escapedCount -eq 0) {
    Write-Host "No escaped PNG MIME values found; nothing changed."
    exit 0
}

$json = $json.Replace('image\/png', 'image/png')
$jsonBytes = [Text.Encoding]::UTF8.GetBytes($json)
$paddedJsonLength = [int](4 * [Math]::Ceiling($jsonBytes.Length / 4.0))
$oldBinaryOffset = 20 + $jsonLength
$binaryLength = $source.Length - $oldBinaryOffset
$newLength = 20 + $paddedJsonLength + $binaryLength
$output = New-Object byte[] $newLength

[Array]::Copy($source, 0, $output, 0, 12)
[Array]::Copy([BitConverter]::GetBytes($newLength), 0, $output, 8, 4)
[Array]::Copy([BitConverter]::GetBytes($paddedJsonLength), 0, $output, 12, 4)
[Array]::Copy($source, 16, $output, 16, 4)
[Array]::Copy($jsonBytes, 0, $output, 20, $jsonBytes.Length)
for ($index = 20 + $jsonBytes.Length; $index -lt 20 + $paddedJsonLength; $index++) {
    $output[$index] = 0x20
}
[Array]::Copy($source, $oldBinaryOffset, $output, 20 + $paddedJsonLength, $binaryLength)

[IO.File]::WriteAllBytes($resolved, $output)
Write-Host "Normalized $escapedCount embedded PNG MIME values in $resolved"
