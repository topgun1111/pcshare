# Part 10: builds sample_chart.xlsx (pure stdlib).
#  Sheet "Data":  clustered column chart (2 series, title, axis titles, legend at the bottom, theme + rgb colours; twoCellAnchor E2:M17)
#                 line chart with markers (North) + smooth line without markers (South), legend right (oneCellAnchor at E19)
#  Sheet "Bars":  horizontal STACKED bar chart whose XML has NO cached values (refs only: the reader must take them from the cells), legend top, axis min / max / major set
#  Sheet "Pie":   a pie chart (not drawn in Part 10: must show the "not drawn yet" box)
# Data: months Jan..Jun; North 120 150 90 180 210 170; South 80 95 130 110 60 140.
import zipfile
NS='xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
C='xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
XDR='xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
HDR='<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
months=['Jan','Feb','Mar','Apr','May','Jun']; N=[120,150,90,180,210,170]; S=[80,95,130,110,60,140]

def istr(ref,t,s=0):
    sa=(' s="%d"'%s) if s else ''
    return '<c r="%s" t="inlineStr"%s><is><t>%s</t></is></c>'%(ref,sa,t)
def sheet_data():
    rows='<row r="1">'+istr('A1','Month',1)+istr('B1','North',1)+istr('C1','South',1)+'</row>'
    for i,m in enumerate(months):
        r=i+2; rows+=f'<row r="{r}">'+istr(f'A{r}',m)+f'<c r="B{r}"><v>{N[i]}</v></c><c r="C{r}"><v>{S[i]}</v></c></row>'
    return rows
def sheet(drawing=True, extra=''):
    return (f'{HDR}<worksheet {NS}><sheetFormatPr defaultRowHeight="15"/><cols><col min="1" max="3" width="10.7109375" customWidth="1"/></cols><sheetData>{sheet_data()}</sheetData>'
            +('<drawing r:id="rId1"/>' if drawing else '')+'</worksheet>')

def strcache(vals): return f'<c:strCache><c:ptCount val="{len(vals)}"/>'+''.join(f'<c:pt idx="{i}"><c:v>{v}</c:v></c:pt>' for i,v in enumerate(vals))+'</c:strCache>'
def numcache(vals): return f'<c:numCache><c:formatCode>General</c:formatCode><c:ptCount val="{len(vals)}"/>'+''.join(f'<c:pt idx="{i}"><c:v>{v}</c:v></c:pt>' for i,v in enumerate(vals))+'</c:numCache>'
def ser(idx, name, nref, col, vals, fill, line=False, marker=None, smooth=None, cache=True):
    sp=(f'<c:spPr><a:ln w="28575" cap="rnd"><a:solidFill>{fill}</a:solidFill><a:round/></a:ln></c:spPr>' if line else f'<c:spPr><a:solidFill>{fill}</a:solidFill></c:spPr>')
    mk='' if marker is None else (f'<c:marker><c:symbol val="{marker}"/>'+('<c:size val="7"/>' if marker!='none' else '')+'</c:marker>')
    tx=f'<c:tx><c:strRef><c:f>{nref}</c:f>'+(strcache([name]) if cache else '')+'</c:strRef></c:tx>'
    cat=f'<c:cat><c:strRef><c:f>Data!$A$2:$A$7</c:f>'+(strcache(months) if cache else '')+'</c:strRef></c:cat>'
    val=f'<c:val><c:numRef><c:f>{col}</c:f>'+(numcache(vals) if cache else '')+'</c:numRef></c:val>'
    sm='' if smooth is None else f'<c:smooth val="{smooth}"/>'
    return f'<c:ser><c:idx val="{idx}"/><c:order val="{idx}"/>{tx}{sp}{mk}{cat}{val}{sm}</c:ser>'
def title(t,sz=1400,bold=0): return f'<c:title><c:tx><c:rich><a:bodyPr/><a:p><a:pPr><a:defRPr sz="{sz}" b="{bold}"/></a:pPr><a:r><a:rPr lang="en-US" sz="{sz}" b="{bold}"/><a:t>{t}</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>'
def axes(cattitle=None,valtitle=None,valextra='',catpos='b',valpos='l',catextra=''):
    ct=title(cattitle,1000,1) if cattitle else ''
    vt=title(valtitle,1000,1) if valtitle else ''
    return (f'<c:catAx><c:axId val="111"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="{catpos}"/>{ct}<c:numFmt formatCode="General" sourceLinked="1"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:crossAx val="222"/><c:crosses val="autoZero"/><c:auto val="1"/><c:lblAlgn val="ctr"/><c:lblOffset val="100"/><c:noMultiLvlLbl val="0"/>{catextra}</c:catAx>'
            f'<c:valAx><c:axId val="222"/>{valextra}<c:delete val="0"/><c:axPos val="{valpos}"/><c:majorGridlines><c:spPr><a:ln w="9525"><a:solidFill><a:srgbClr val="D9D9D9"/></a:solidFill></a:ln></c:spPr></c:majorGridlines>{vt}<c:numFmt formatCode="General" sourceLinked="1"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:crossAx val="111"/><c:crosses val="autoZero"/><c:crossBetween val="between"/></c:valAx>')
def chartspace(t,plot,legend):
    lg=f'<c:legend><c:legendPos val="{legend}"/><c:overlay val="0"/></c:legend>' if legend else ''
    tt=title(t) if t else ''
    return f'{HDR}<c:chartSpace {C}><c:roundedCorners val="0"/><c:chart>{tt}<c:autoTitleDeleted val="{0 if t else 1}"/><c:plotArea><c:layout/>{plot}</c:plotArea>{lg}<c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/></c:chart></c:chartSpace>'
acc1='<a:schemeClr val="accent1"/>'; red='<a:srgbClr val="E4572E"/>'; grn='<a:srgbClr val="70AD47"/>'
chart1=chartspace('Sales by month',
    '<c:barChart><c:barDir val="col"/><c:grouping val="clustered"/><c:varyColors val="0"/>'
    +ser(0,'North','Data!$B$1','Data!$B$2:$B$7',N,acc1)+ser(1,'South','Data!$C$1','Data!$C$2:$C$7',S,red)
    +'<c:gapWidth val="80"/><c:axId val="111"/><c:axId val="222"/></c:barChart>'+axes('Month','Units'),'b')
chart2=chartspace('Trend',
    '<c:lineChart><c:grouping val="standard"/><c:varyColors val="0"/>'
    +ser(0,'North','Data!$B$1','Data!$B$2:$B$7',N,acc1,line=True,marker='circle',smooth=0)+ser(1,'South','Data!$C$1','Data!$C$2:$C$7',S,red,line=True,marker='none',smooth=1)
    +'<c:marker val="1"/><c:axId val="111"/><c:axId val="222"/></c:lineChart>'+axes(),'r')
chart3=chartspace('Stacked (no cache)',
    '<c:barChart><c:barDir val="bar"/><c:grouping val="stacked"/><c:varyColors val="0"/>'
    +ser(0,'North','Data!$B$1','Data!$B$2:$B$7',N,acc1,cache=False)+ser(1,'South','Data!$C$1','Data!$C$2:$C$7',S,grn,cache=False)
    +'<c:gapWidth val="60"/><c:overlap val="100"/><c:axId val="111"/><c:axId val="222"/></c:barChart>'
    +axes(valextra='',catpos='l',valpos='b').replace('<c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="b"/>','<c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="l"/>').replace('<c:axId val="222"/><c:delete','<c:axId val="222"/><c:scaling><c:orientation val="minMax"/><c:max val="400"/><c:min val="0"/></c:scaling><c:delete').replace('<c:crossBetween val="between"/>','<c:crossBetween val="between"/><c:majorUnit val="100"/>'),'t')
chart4=chartspace('Share',
    '<c:pieChart><c:varyColors val="1"/>'+ser(0,'North','Data!$B$1','Data!$B$2:$B$7',N,acc1).replace('<c:spPr><a:solidFill><a:schemeClr val="accent1"/></a:solidFill></c:spPr>','')+'</c:pieChart>','r')
def anchor_two(c1,r1,c2,r2,rid,i): return f'<xdr:twoCellAnchor><xdr:from><xdr:col>{c1}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>{r1}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>{c2}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>{r2}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>{frame(rid,i)}<xdr:clientData/></xdr:twoCellAnchor>'
def anchor_one(c1,r1,cx,cy,rid,i): return f'<xdr:oneCellAnchor><xdr:from><xdr:col>{c1}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>{r1}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:ext cx="{cx}" cy="{cy}"/>{frame(rid,i)}<xdr:clientData/></xdr:oneCellAnchor>'
def frame(rid,i): return f'<xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="{i+1}" name="Chart {i}"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart r:id="{rid}"/></a:graphicData></a:graphic></xdr:graphicFrame>'
def drawing(body): return f'{HDR}<xdr:wsDr {XDR}>{body}</xdr:wsDr>'
REL='http://schemas.openxmlformats.org/officeDocument/2006/relationships'
def rels(items): return f'{HDR}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'+''.join(f'<Relationship Id="{i}" Type="{REL}/{t}" Target="{g}"/>' for i,t,g in items)+'</Relationships>'
d1=drawing(anchor_two(4,1,13,16,'rId1',2)+anchor_one(4,18,4572000,2857500,'rId2',3))   # 480 x 300 px
d2=drawing(anchor_two(4,1,13,16,'rId1',2)); d3=drawing(anchor_two(4,1,12,16,'rId1',2))
theme=(f'{HDR}<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Sample"><a:themeElements><a:clrScheme name="Sample"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F2A44"/></a:dk2><a:lt2><a:srgbClr val="EEECE1"/></a:lt2>'
  '<a:accent1><a:srgbClr val="1F6F8B"/></a:accent1><a:accent2><a:srgbClr val="E4572E"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>'
  '<a:fontScheme name="Sample"><a:majorFont><a:latin typeface="Calibri Light"/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/></a:minorFont></a:fontScheme></a:themeElements></a:theme>')
styles=(f'{HDR}<styleSheet {NS}><fonts count="2"><font><sz val="11"/><color theme="1"/><name val="Calibri"/></font><font><b/><sz val="11"/><color theme="1"/><name val="Calibri"/></font></fonts>'
  '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
  '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs></styleSheet>')
CT='http://schemas.openxmlformats.org/officeDocument/2006/relationships'
ct=(f'{HDR}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>'
  '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
  +''.join(f'<Override PartName="/xl/worksheets/sheet{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' for i in (1,2,3))
  +'<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>'
  +''.join(f'<Override PartName="/xl/drawings/drawing{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>' for i in (1,2,3))
  +''.join(f'<Override PartName="/xl/charts/chart{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>' for i in (1,2,3,4))+'</Types>')
wb=(f'{HDR}<workbook {NS}><sheets><sheet name="Data" sheetId="1" r:id="rId1"/><sheet name="Bars" sheetId="2" r:id="rId2"/><sheet name="Pie" sheetId="3" r:id="rId3"/></sheets></workbook>')
with zipfile.ZipFile('tools/office_selftest/sample_chart.xlsx','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('[Content_Types].xml',ct)
    z.writestr('_rels/.rels',rels([('rId1','officeDocument','xl/workbook.xml')]))
    z.writestr('xl/workbook.xml',wb)
    z.writestr('xl/_rels/workbook.xml.rels',rels([('rId1','worksheet','worksheets/sheet1.xml'),('rId2','worksheet','worksheets/sheet2.xml'),('rId3','worksheet','worksheets/sheet3.xml'),('rId4','styles','styles.xml'),('rId5','theme','theme/theme1.xml')]))
    for i in (1,2,3):
        z.writestr(f'xl/worksheets/sheet{i}.xml',sheet())
        z.writestr(f'xl/worksheets/_rels/sheet{i}.xml.rels',rels([('rId1','drawing',f'../drawings/drawing{i}.xml')]))
    z.writestr('xl/drawings/drawing1.xml',d1); z.writestr('xl/drawings/drawing2.xml',d2); z.writestr('xl/drawings/drawing3.xml',d3)
    z.writestr('xl/drawings/_rels/drawing1.xml.rels',rels([('rId1','chart','../charts/chart1.xml'),('rId2','chart','../charts/chart2.xml')]))
    z.writestr('xl/drawings/_rels/drawing2.xml.rels',rels([('rId1','chart','../charts/chart3.xml')]))
    z.writestr('xl/drawings/_rels/drawing3.xml.rels',rels([('rId1','chart','../charts/chart4.xml')]))
    for i,c in enumerate((chart1,chart2,chart3,chart4),1): z.writestr(f'xl/charts/chart{i}.xml',c)
    z.writestr('xl/styles.xml',styles); z.writestr('xl/theme/theme1.xml',theme)
print('ok')
