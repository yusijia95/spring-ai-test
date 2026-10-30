$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

$curPath = "C:\Users\yu130\.trae-cn\attachments\6aca061bcea8ab437dd7b0e6\current_extracted\word\document.xml"
$pilotPath = "C:\Users\yu130\.trae-cn\attachments\6aca061bcea8ab437dd7b0e6\pilot_extracted\word\document.xml"
$srcDir = "C:\Users\yu130\.trae-cn\attachments\6aca061bcea8ab437dd7b0e6\current_extracted"
$outDocx = "C:\Users\yu130\.trae-cn\attachments\6aca061bcea8ab437dd7b0e6\20261008_modified.docx"

Copy-Item $curPath "$curPath.bak" -Force

$cur = New-Object System.Xml.XmlDocument
$cur.PreserveWhitespace = $true
$cur.Load($curPath)
$pilot = New-Object System.Xml.XmlDocument
$pilot.PreserveWhitespace = $true
$pilot.Load($pilotPath)

$wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
$w14Ns = "http://schemas.microsoft.com/office/word/2010/wordml"
$nsm = New-Object System.Xml.XmlNamespaceManager($cur.NameTable)
$nsm.AddNamespace("w", $wNs)
$nsm.AddNamespace("w14", $w14Ns)
$pnsm = New-Object System.Xml.XmlNamespaceManager($pilot.NameTable)
$pnsm.AddNamespace("w", $wNs)
$pnsm.AddNamespace("w14", $w14Ns)

function Get-PText($p, $m) {
    $ts = $p.SelectNodes(".//w:t", $m)
    $s = ""
    foreach($t in $ts){ $s += $t.InnerText }
    return $s
}

$pParas = $pilot.SelectNodes("//w:p", $pnsm)
$curParas = $cur.SelectNodes("//w:p", $nsm)

$introText = Get-PText $pParas[97] $pnsm
$subTexts = @()
for ($i=98; $i -le 103; $i++) { $subTexts += ,(Get-PText $pParas[$i] $pnsm) }

$prefixMap = @{}
$pn = 104
for ($num=2; $num -le 14; $num++) {
    $t = Get-PText $pParas[$pn] $pnsm
    $ci = $t.IndexOf([char]0xFF09)
    $prefixMap[$num] = $t.Substring(0, $ci+1)
    $pn++
}

function Get-WTs($p) { return @($p.SelectNodes(".//w:t", $nsm)) }
function CS([int[]]$codes) {
    $sb = New-Object System.Text.StringBuilder
    foreach($c in $codes){ [void]$sb.Append([char]$c) }
    return $sb.ToString()
}
$DUNHAO = [char]0x3001
$DSTR = [string]$DUNHAO

# 1. Section 6 title
$p = $curParas[81]; $wts = @(Get-WTs $p)
$wts[1].InnerText = $wts[1].InnerText.Replace((CS 0x6258,0x7BA1), (CS 0x76D1,0x7BA1))

# 2. Section 6 table header
$p = $curParas[82]; $wts = @(Get-WTs $p)
$wts[0].InnerText = $wts[0].InnerText.Substring(2)

# 3. Section 7 title
$p = $curParas[91]; $wts = @(Get-WTs $p)
$wts[1].InnerText = $wts[1].InnerText.Replace((CS 0x627F,0x8BFA), (CS 0x8981,0x6C42,0x53CA,0x627F,0x8BFA))

# Category A renumber
function Renumber-A($p, $prefix) {
    $wts = @(Get-WTs $p)
    $t0 = $wts[0].InnerText
    $di = $t0.IndexOf($DSTR)
    $wts[0].InnerText = $prefix + $t0.Substring($di+1)
}
Renumber-A $curParas[93] $prefixMap[2]
Renumber-A $curParas[94] $prefixMap[3]
Renumber-A $curParas[95] $prefixMap[4]
Renumber-A $curParas[96] $prefixMap[5]
Renumber-A $curParas[103] $prefixMap[12]

# P97 renumber + dunhao fix
$p = $curParas[97]; $wts = @(Get-WTs $p)
$t0 = $wts[0].InnerText; $di = $t0.IndexOf($DSTR)
$body = $t0.Substring($di+1)
$body = $body.Replace((CS 0x300B,0x300A), (CS 0x300B,0x3001,0x300A))
$wts[0].InnerText = $prefixMap[6] + $body

# Category B renumber (number own w:t, dunhao start of next)
function Renumber-B($p, $prefix) {
    $wts = @(Get-WTs $p)
    $di = -1
    for ($i=0; $i -lt $wts.Count; $i++) {
        if ($wts[$i].InnerText.Length -gt 0 -and $wts[$i].InnerText.StartsWith($DSTR)) { $di = $i; break }
    }
    if ($di -lt 1) { throw "B: no dunhao w:t" }
    $wts[$di-1].InnerText = $prefix
    $wts[$di].InnerText = $wts[$di].InnerText.Substring(1)
}
Renumber-B $curParas[98] $prefixMap[7]
Renumber-B $curParas[99] $prefixMap[8]
Renumber-B $curParas[100] $prefixMap[9]
Renumber-B $curParas[101] $prefixMap[10]

# Category C renumber (split digits + dunhao w:t)
function Renumber-C($p, $prefix) {
    $wts = @(Get-WTs $p)
    $di = -1
    for ($i=0; $i -lt $wts.Count; $i++) {
        if ($wts[$i].InnerText.Length -gt 0 -and $wts[$i].InnerText.StartsWith($DSTR)) { $di = $i; break }
    }
    if ($di -lt 2) { throw "C: no split number" }
    $wts[$di-2].InnerText = $prefix
    $wts[$di-1].InnerText = ""
    $wts[$di].InnerText = $wts[$di].InnerText.Substring(1)
}
Renumber-C $curParas[102] $prefixMap[11]
Renumber-C $curParas[104] $prefixMap[13]
Renumber-C $curParas[107] $prefixMap[14]

# P92: replace text with intro, insert 6 sub-item paragraphs
$p92 = $curParas[92]; $wts92 = @(Get-WTs $p92)
$wts92[0].InnerText = $introText
$wts92[1].InnerText = ""

$template = $curParas[94]
$parent = $p92.ParentNode
$ref = $p92
for ($i=0; $i -lt 6; $i++) {
    $clone = $template.CloneNode($true)
    $clone.RemoveAttribute("paraId", $w14Ns)
    $ct = $clone.SelectSingleNode(".//w:t", $nsm)
    $ct.InnerText = $subTexts[$i]
    $parent.InsertAfter($clone, $ref)
    $ref = $clone
}

# Save
$settings = New-Object System.Xml.XmlWriterSettings
$settings.Encoding = New-Object System.Text.UTF8Encoding($false)
$settings.Indent = $false
$settings.OmitXmlDeclaration = $false
$writer = [System.Xml.XmlWriter]::Create($curPath, $settings)
$cur.Save($writer)
$writer.Close()
Write-Host "XML saved."

# Re-zip into docx
if (Test-Path $outDocx) { Remove-Item $outDocx -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory($srcDir, $outDocx, [System.IO.Compression.CompressionLevel]::Optimal, $false)
Write-Host "DOCX created: $outDocx"

# Verify: re-extract section 6 & 7 text
$v = New-Object System.Xml.XmlDocument
$v.PreserveWhitespace = $true
$v.Load($curPath)
$vm = New-Object System.Xml.XmlNamespaceManager($v.NameTable)
$vm.AddNamespace("w", $wNs)
$vp = $v.SelectNodes("//w:p", $vm)
$idx = 0
foreach($pp in $vp) {
    $ts = $pp.SelectNodes(".//w:t", $vm)
    $line = ""; foreach($t in $ts){ $line += $t.InnerText }
    if ($idx -ge 80 -and $idx -le 120) {
        Write-Host ("V[{0}] {1}" -f $idx, $line)
    }
    $idx++
}
Write-Host "DONE"
