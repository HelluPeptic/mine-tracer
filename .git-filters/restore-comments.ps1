# PowerShell script to restore original files (passthrough)
# This just passes the content through unchanged for the smudge filter
while ($null -ne ($line = [Console]::ReadLine())) {
    Write-Output $line
}
