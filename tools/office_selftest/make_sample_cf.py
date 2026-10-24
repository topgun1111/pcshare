# Part 9: builds sample_cf.xlsx (pure stdlib). Sheet "Sales": theme-coloured header (theme 4 + white theme 0 bold, medium bottom rule), 3-colour scale (B), data bar (C),
# cellIs > 50 (D), top-2 (E), expression $B2>=$C2 (A), a theme-tinted text cell, a blank cell with a tinted fill, 2 cell comments (B2, D5), frozen header, custom column widths.
# Sheet "NoGrid": showGridLines=0 and one cell with explicit thin borders. Theme: custom accent1 1F6F8B, accent2 E4572E.
# Expected: B2 fill #f8696b, B6 (91) fill #63be7b; C bars 10% (50) .. 90% (300); D3 D5 D7 fill #ffc7ce; E5 E7 bold + fill #c6efce; A6 italic #7030a0.
import zipfile
NS='xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
HDR='<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
names=['Alpha','Bravo','Charlie','Delta','Echo','Foxtrot','Golf','Hotel']
B=[12,35,58,77,91,40,66,23]; C=[100,250,175,300,50,225,125,275]; D=[20,55,40,65,30,80,45,10]; E=[5,9,3,12,7,12,1,8]
sst=['Name','Score','Sales','Target','Rank','Tinted text','Sheet two','No grid here']+names
def s(t): return sst.index(t)
rows=''
rows+='<row r="1">'+''.join(f'<c r="{c}1" s="1" t="s"><v>{s(t)}</v></c>' for c,t in zip('ABCDE',['Name','Score','Sales','Target','Rank']))+'<c r="F1" s="5"/></row>'
for i,n in enumerate(names):
    r=i+2
    rows+=f'<row r="{r}"><c r="A{r}" t="s"><v>{s(n)}</v></c><c r="B{r}" s="3"><v>{B[i]}</v></c><c r="C{r}" s="3"><v>{C[i]}</v></c><c r="D{r}" s="3"><v>{D[i]}</v></c><c r="E{r}"><v>{E[i]}</v></c>'+('<c r="F2" s="5"/>' if r==2 else '')+'</row>'
rows+=f'<row r="10"><c r="A10" s="2" t="s"><v>{s("Tinted text")}</v></c></row>'
cf=('<conditionalFormatting sqref="B2:B9"><cfRule type="colorScale" priority="1"><colorScale><cfvo type="min"/><cfvo type="percentile" val="50"/><cfvo type="max"/>'
    '<color rgb="FFF8696B"/><color rgb="FFFFEB84"/><color rgb="FF63BE7B"/></colorScale></cfRule></conditionalFormatting>'
    '<conditionalFormatting sqref="C2:C9"><cfRule type="dataBar" priority="2"><dataBar><cfvo type="min"/><cfvo type="max"/><color rgb="FF638EC6"/></dataBar></cfRule></conditionalFormatting>'
    '<conditionalFormatting sqref="D2:D9"><cfRule type="cellIs" dxfId="0" priority="3" operator="greaterThan"><formula>50</formula></cfRule></conditionalFormatting>'
    '<conditionalFormatting sqref="E2:E9"><cfRule type="top10" dxfId="1" priority="4" rank="2"/></conditionalFormatting>'
    '<conditionalFormatting sqref="A2:A9"><cfRule type="expression" dxfId="2" priority="5"><formula>$B2&gt;=$C2</formula></cfRule></conditionalFormatting>')
sheet1=(f'{HDR}<worksheet {NS}><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>'
        '<sheetFormatPr defaultRowHeight="15"/><cols><col min="1" max="1" width="14.7109375" customWidth="1"/><col min="2" max="5" width="10.7109375" customWidth="1"/><col min="6" max="6" width="12.7109375" customWidth="1"/></cols>'
        f'<sheetData>{rows}</sheetData>{cf}<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/></worksheet>')
sheet2=(f'{HDR}<worksheet {NS}><sheetViews><sheetView showGridLines="0" workbookViewId="0"/></sheetViews><sheetFormatPr defaultRowHeight="15"/><cols><col min="2" max="2" width="20.7109375" customWidth="1"/></cols>'
        f'<sheetData><row r="1"><c r="A1" t="s"><v>{s("Sheet two")}</v></c></row><row r="2"><c r="B2" s="4" t="s"><v>{s("No grid here")}</v></c></row></sheetData></worksheet>')
sharedstrings=f'{HDR}<sst {NS} count="{len(sst)}" uniqueCount="{len(sst)}">'+''.join(f'<si><t>{t}</t></si>' for t in sst)+'</sst>'
styles=(f'{HDR}<styleSheet {NS}><numFmts count="0"/>'
  '<fonts count="3"><font><sz val="11"/><color theme="1"/><name val="Calibri"/><family val="2"/></font>'
  '<font><b/><sz val="11"/><color theme="0"/><name val="Calibri"/><family val="2"/></font>'
  '<font><sz val="11"/><color theme="5" tint="-0.249977111117893"/><name val="Calibri"/><family val="2"/></font></fonts>'
  '<fills count="5"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>'
  '<fill><patternFill patternType="solid"><fgColor theme="4"/><bgColor indexed="64"/></patternFill></fill>'
  '<fill><patternFill patternType="solid"><fgColor theme="5" tint="0.59999389629810485"/><bgColor indexed="64"/></patternFill></fill>'
  '<fill><patternFill patternType="solid"><fgColor rgb="FFFFFF00"/><bgColor indexed="64"/></patternFill></fill></fills>'
  '<borders count="3"><border><left/><right/><top/><bottom/><diagonal/></border>'
  '<border><left/><right/><top/><bottom style="medium"><color theme="1"/></bottom><diagonal/></border>'
  '<border><left style="thin"><color indexed="10"/></left><right style="thin"><color indexed="10"/></right><top style="thin"><color indexed="10"/></top><bottom style="thin"><color indexed="10"/></bottom><diagonal/></border></borders>'
  '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
  '<cellXfs count="6"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
  '<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/>'
  '<xf numFmtId="0" fontId="2" fillId="3" borderId="0" xfId="0" applyFont="1" applyFill="1"/>'
  '<xf numFmtId="2" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
  '<xf numFmtId="0" fontId="0" fillId="0" borderId="2" xfId="0" applyBorder="1"/>'
  '<xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1"/></cellXfs>'
  '<dxfs count="3">'
  '<dxf><font><color rgb="FF9C0006"/></font><fill><patternFill><bgColor rgb="FFFFC7CE"/></patternFill></fill></dxf>'
  '<dxf><font><b/><color rgb="FF006100"/></font><fill><patternFill><bgColor rgb="FFC6EFCE"/></patternFill></fill></dxf>'
  '<dxf><font><i/><color rgb="FF7030A0"/></font></dxf></dxfs></styleSheet>')
theme=(f'{HDR}<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Sample"><a:themeElements><a:clrScheme name="Sample">'
  '<a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F2A44"/></a:dk2><a:lt2><a:srgbClr val="EEECE1"/></a:lt2>'
  '<a:accent1><a:srgbClr val="1F6F8B"/></a:accent1><a:accent2><a:srgbClr val="E4572E"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4>'
  '<a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>'
  '<a:fontScheme name="Sample"><a:majorFont><a:latin typeface="Cambria"/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/></a:minorFont></a:fontScheme></a:themeElements></a:theme>')
comments=(f'{HDR}<comments {NS}><authors><author>Tester</author></authors><commentList>'
  '<comment ref="B2" authorId="0"><text><r><rPr><b/><sz val="9"/></rPr><t>Tester:</t></r><r><rPr><sz val="9"/></rPr><t xml:space="preserve">\nCheck this value</t></r></text></comment>'
  '<comment ref="D5" authorId="0"><text><r><t>Above target</t></r></text></comment></commentList></comments>')
wb=(f'{HDR}<workbook {NS}><sheets><sheet name="Sales" sheetId="1" r:id="rId1"/><sheet name="NoGrid" sheetId="2" r:id="rId2"/></sheets></workbook>')
R='http://schemas.openxmlformats.org/officeDocument/2006/relationships'
wbrels=(f'{HDR}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="{R}/worksheet" Target="worksheets/sheet1.xml"/>'
  f'<Relationship Id="rId2" Type="{R}/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="{R}/styles" Target="styles.xml"/>'
  f'<Relationship Id="rId4" Type="{R}/sharedStrings" Target="sharedStrings.xml"/><Relationship Id="rId5" Type="{R}/theme" Target="theme/theme1.xml"/></Relationships>')
s1rels=f'{HDR}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="{R}/comments" Target="../comments1.xml"/></Relationships>'
ct=(f'{HDR}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>'
  '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
  '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
  '<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
  '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
  '<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>'
  '<Override PartName="/xl/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>'
  '<Override PartName="/xl/comments1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.comments+xml"/></Types>')
rels=f'{HDR}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="{R}/officeDocument" Target="xl/workbook.xml"/></Relationships>'
with zipfile.ZipFile('sample_cf.xlsx','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('[Content_Types].xml',ct); z.writestr('_rels/.rels',rels); z.writestr('xl/workbook.xml',wb); z.writestr('xl/_rels/workbook.xml.rels',wbrels)
    z.writestr('xl/worksheets/sheet1.xml',sheet1); z.writestr('xl/worksheets/sheet2.xml',sheet2); z.writestr('xl/worksheets/_rels/sheet1.xml.rels',s1rels)
    z.writestr('xl/styles.xml',styles); z.writestr('xl/sharedStrings.xml',sharedstrings); z.writestr('xl/theme/theme1.xml',theme); z.writestr('xl/comments1.xml',comments)
print('sample_cf.xlsx written')
