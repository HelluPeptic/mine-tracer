# PowerShell script to strip comments from Java files
# Read all input from stdin
$content = @()
while ($null -ne ($line = [Console]::ReadLine())) {
    $content += $line
}

# Join content and process
$text = $content -join "`n"

if ([string]::IsNullOrEmpty($text)) {
    # If no content, just exit
    exit 0
}

# Remove single-line comments (// comments) but preserve URLs and other important // patterns
$text = $text -replace '(?m)^\s*//(?![:/]).*$', ''

# Remove multi-line comments (/* ... */) but be more careful about removal
$text = $text -replace '(?ms)/\*(?!\*/).*?\*/', ''

# Remove empty lines that resulted from comment removal
$lines = $text -split "`n" | Where-Object { $_.Trim() -ne '' }

# Output the cleaned text
$lines -join "`n"
