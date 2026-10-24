# Part 11: builds sample_chart2.xlsx (pure stdlib). Run from the repo root: python3 tools/office_selftest/make_sample_chart2.py
#  Sheet "Pie":     pie chart (5 slices, percent labels, slice 2 recoloured by dPt, legend right, twoCellAnchor D2:K17)
#                   + doughnut chart (holeSize 55, firstSliceAng 90, legend bottom, oneCellAnchor 420 x 300 at D19)
#  Sheet "Area":    stacked area chart (North + South, legend bottom, axis crossBetween midCat)
#  Sheet "Scatter": scatter chart: series A = markers only (ln noFill, diamonds, marker fill colour), series B = smooth line with circle markers; two value axes with titles
#  Sheet "Pics":    one PNG picture (oneCellAnchor), a rounded rectangle with white text, an ellipse, a text box (no fill, two paragraphs)
import zipfile, zlib, struct
NS='xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
C='xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
XDR='xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
HDR='<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
REL='http://schemas.openxmlformats.org/officeDocument/2006/relationships'
regions=['North','East','South','West','Other']; units=[120,80,60,40,10]
months=['Jan','Feb','Mar','Apr','May','Jun']; N=[120,150,90,180,210,170]; S=[80,95,130,110,60,140]
X=[1,2,3,4,5,6,7,8]; YA=[2.1,3.9,6.2,7.8,10.1,12.2,13.8,16.1]; YB=[1.0,2.5,2.0,5.5,4.0,8.5,7.0,11.0]

def istr(ref,t,s=0): return '<c r="%s" t="inlineStr"%s><is><t>%s</t></is></c>'%(ref,(' s="%d"'%s) if s else '',t)
def row(r,cells): return f'<row r="{r}">'+''.join(cells)+'</row>'
def num(ref,v): return f'<c r="{ref}"><v>{v}</v></c>'
def sheet(rows,drawing=True): return f'{HDR}<worksheet {NS}><sheetFormatPr defaultRowHeight="15"/><cols><col min="1" max="3" width="10.7109375" customWidth="1"/></cols><sheetData>{"".join(rows)}</sheetData>'+('<drawing r:id="rId1"/>' if drawing else '')+'</worksheet>'
sh_pie=sheet([row(1,[istr('A1','Region',1),istr('B1','Units',1)])]+[row(i+2,[istr(f'A{i+2}',regions[i]),num(f'B{i+2}',units[i])]) for i in range(5)])
sh_area=sheet([row(1,[istr('A1','Month',1),istr('B1','North',1),istr('C1','South',1)])]+[row(i+2,[istr(f'A{i+2}',months[i]),num(f'B{i+2}',N[i]),num(f'C{i+2}',S[i])]) for i in range(6)])
sh_sc=sheet([row(1,[istr('A1','X',1),istr('B1','Series A',1),istr('C1','Series B',1)])]+[row(i+2,[num(f'A{i+2}',X[i]),num(f'B{i+2}',YA[i]),num(f'C{i+2}',YB[i])]) for i in range(8)])
sh_pics=sheet([row(1,[istr('A1','Pictures and shapes',1)])])

def strcache(vals): return f'<c:strCache><c:ptCount val="{len(vals)}"/>'+''.join(f'<c:pt idx="{i}"><c:v>{v}</c:v></c:pt>' for i,v in enumerate(vals))+'</c:strCache>'
def numcache(vals): return f'<c:numCache><c:formatCode>General</c:formatCode><c:ptCount val="{len(vals)}"/>'+''.join(f'<c:pt idx="{i}"><c:v>{v}</c:v></c:pt>' for i,v in enumerate(vals))+'</c:numCache>'
def title(t,sz=1400,bold=0): return f'<c:title><c:tx><c:rich><a:bodyPr/><a:p><a:pPr><a:defRPr sz="{sz}" b="{bold}"/></a:pPr><a:r><a:rPr lang="en-US" sz="{sz}" b="{bold}"/><a:t>{t}</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>'
def chartspace(t,plot,legend):
    lg=f'<c:legend><c:legendPos val="{legend}"/><c:overlay val="0"/></c:legend>' if legend else ''
    tt=title(t) if t else ''
    return f'{HDR}<c:chartSpace {C}><c:roundedCorners val="0"/><c:chart>{tt}<c:autoTitleDeleted val="{0 if t else 1}"/><c:plotArea><c:layout/>{plot}</c:plotArea>{lg}<c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/></c:chart></c:chartSpace>'
def tx(ref,name): return f'<c:tx><c:strRef><c:f>{ref}</c:f>{strcache([name])}</c:strRef></c:tx>'
def cat(ref,vals): return f'<c:cat><c:strRef><c:f>{ref}</c:f>{strcache(vals)}</c:strRef></c:cat>'
def val(ref,vals,tag='val'): return f'<c:{tag}><c:numRef><c:f>{ref}</c:f>{numcache(vals)}</c:numRef></c:{tag}>'
def fill(c): return f'<a:solidFill>{c}</a:solidFill>'
acc1='<a:schemeClr val="accent1"/>'; red='<a:srgbClr val="E4572E"/>'; grn='<a:srgbClr val="70AD47"/>'; gold='<a:srgbClr val="FFC000"/>'
def axes_cat_val(cat_title=None,val_title=None):
    ct=title(cat_title,1000,1) if cat_title else ''; vt=title(val_title,1000,1) if val_title else ''
    return (f'<c:catAx><c:axId val="111"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="b"/>{ct}<c:numFmt formatCode="General" sourceLinked="1"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:crossAx val="222"/><c:crosses val="autoZero"/><c:auto val="1"/><c:lblAlgn val="ctr"/><c:lblOffset val="100"/><c:noMultiLvlLbl val="0"/></c:catAx>'
            f'<c:valAx><c:axId val="222"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="l"/><c:majorGridlines><c:spPr><a:ln w="9525">{fill("<a:srgbClr val=\"D9D9D9\"/>")}</a:ln></c:spPr></c:majorGridlines>{vt}<c:numFmt formatCode="General" sourceLinked="1"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:crossAx val="111"/><c:crosses val="autoZero"/><c:crossBetween val="midCat"/></c:valAx>')
def axes_xy(xt,yt):
    g=f'<c:majorGridlines><c:spPr><a:ln w="9525">{fill("<a:srgbClr val=\"D9D9D9\"/>")}</a:ln></c:spPr></c:majorGridlines>'
    return (f'<c:valAx><c:axId val="111"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="b"/>{g}{title(xt,1000,1)}<c:numFmt formatCode="General" sourceLinked="1"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:crossAx val="222"/><c:crosses val="autoZero"/><c:crossBetween val="midCat"/></c:valAx>'
            f'<c:valAx><c:axId val="222"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="l"/>{g}{title(yt,1000,1)}<c:numFmt formatCode="General" sourceLinked="1"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:crossAx val="111"/><c:crosses val="autoZero"/><c:crossBetween val="midCat"/></c:valAx>')

lbl_pct='<c:dLbls><c:spPr><a:noFill/></c:spPr><c:showLegendKey val="0"/><c:showVal val="0"/><c:showCatName val="0"/><c:showSerName val="0"/><c:showPercent val="1"/><c:showBubbleSize val="0"/></c:dLbls>'
pie_ser=(f'<c:ser><c:idx val="0"/><c:order val="0"/>{tx("Pie!$B$1","Units")}'
         f'<c:dPt><c:idx val="1"/><c:bubble3D val="0"/><c:spPr>{fill(gold)}</c:spPr></c:dPt>{lbl_pct}'
         f'{cat("Pie!$A$2:$A$6",regions)}{val("Pie!$B$2:$B$6",units)}</c:ser>')
chart5=chartspace('Units by region','<c:pieChart><c:varyColors val="1"/>'+pie_ser+'<c:firstSliceAng val="0"/></c:pieChart>','r')
chart6=chartspace('Doughnut','<c:doughnutChart><c:varyColors val="1"/>'+pie_ser.replace(lbl_pct,'')+'<c:firstSliceAng val="90"/><c:holeSize val="55"/></c:doughnutChart>','b')
def area_ser(i,name,nref,vref,vals,col): return f'<c:ser><c:idx val="{i}"/><c:order val="{i}"/>{tx(nref,name)}<c:spPr>{fill(col)}</c:spPr>{cat("Area!$A$2:$A$7",months)}{val(vref,vals)}</c:ser>'
chart7=chartspace('Units by month (stacked)','<c:areaChart><c:grouping val="stacked"/><c:varyColors val="0"/>'+area_ser(0,'North','Area!$B$1','Area!$B$2:$B$7',N,acc1)+area_ser(1,'South','Area!$C$1','Area!$C$2:$C$7',S,red)+'<c:axId val="111"/><c:axId val="222"/></c:areaChart>'+axes_cat_val('Month','Units'),'b')
def sc_ser(i,name,nref,yref,ys,sp,marker,smooth):
    return f'<c:ser><c:idx val="{i}"/><c:order val="{i}"/>{tx(nref,name)}{sp}{marker}{val("Scatter!$A$2:$A$9",X,"xVal")}{val(yref,ys,"yVal")}<c:smooth val="{smooth}"/></c:ser>'
spA='<c:spPr><a:ln w="19050"><a:noFill/></a:ln></c:spPr>'
mkA=f'<c:marker><c:symbol val="diamond"/><c:size val="8"/><c:spPr>{fill(red)}</c:spPr></c:marker>'
spB=f'<c:spPr><a:ln w="28575" cap="rnd">{fill(acc1)}<a:round/></a:ln></c:spPr>'
mkB='<c:marker><c:symbol val="circle"/><c:size val="5"/></c:marker>'
chart8=chartspace('Calibration','<c:scatterChart><c:scatterStyle val="lineMarker"/><c:varyColors val="0"/>'+sc_ser(0,'Series A','Scatter!$B$1','Scatter!$B$2:$B$9',YA,spA,mkA,0)+sc_ser(1,'Series B','Scatter!$C$1','Scatter!$C$2:$C$9',YB,spB,mkB,1)+'<c:axId val="111"/><c:axId val="222"/></c:scatterChart>'+axes_xy('Input','Reading'),'r')

def frame(rid,i): return f'<xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="{i+1}" name="Chart {i}"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart r:id="{rid}"/></a:graphicData></a:graphic></xdr:graphicFrame>'
def frm(c,r): return f'<xdr:from><xdr:col>{c}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>{r}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>'
def to(c,r): return f'<xdr:to><xdr:col>{c}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>{r}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>'
def two(c1,r1,c2,r2,body): return f'<xdr:twoCellAnchor>{frm(c1,r1)}{to(c2,r2)}{body}<xdr:clientData/></xdr:twoCellAnchor>'
def one(c1,r1,cx,cy,body): return f'<xdr:oneCellAnchor>{frm(c1,r1)}<xdr:ext cx="{cx}" cy="{cy}"/>{body}<xdr:clientData/></xdr:oneCellAnchor>'
def drawing(body): return f'{HDR}<xdr:wsDr {XDR}>{body}</xdr:wsDr>'
def rels(items): return f'{HDR}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'+''.join(f'<Relationship Id="{i}" Type="{REL}/{t}" Target="{g}"/>' for i,t,g in items)+'</Relationships>'
d1=drawing(two(3,1,11,16,frame('rId1',2))+one(3,18,4000500,2857500,frame('rId2',3)))   # 420 x 300 px
d2=drawing(two(4,1,13,16,frame('rId1',2))); d3=drawing(two(4,1,12,17,frame('rId1',2)))
pic=('<xdr:pic><xdr:nvPicPr><xdr:cNvPr id="2" name="Picture 1"/><xdr:cNvPicPr/></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>'
     '<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1143000" cy="762000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>')
def sp(i,name,prst,fillxml,lnxml,paras,txbox=False,color=None):
    runs=''.join(f'<a:p><a:pPr algn="{"ctr" if not txbox else "l"}"/><a:r><a:rPr lang="en-US" sz="1400">'+(fill(color) if color else '')+f'</a:rPr><a:t>{t}</a:t></a:r></a:p>' for t in paras)
    body=f'<xdr:txBody><a:bodyPr/><a:lstStyle/>{runs}</xdr:txBody>' if paras else ''
    return (f'<xdr:sp macro="" textlink=""><xdr:nvSpPr><xdr:cNvPr id="{i}" name="{name}"/><xdr:cNvSpPr{" txBox=\"1\"" if txbox else ""}/></xdr:nvSpPr><xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></a:xfrm><a:prstGeom prst="{prst}"><a:avLst/></a:prstGeom>{fillxml}{lnxml}</xdr:spPr>{body}</xdr:sp>')
white='<a:schemeClr val="lt1"/>'
d4=drawing(one(1,2,1143000,762000,pic)
   +two(4,2,7,5,sp(3,'Rounded box','roundRect',fill(acc1),f'<a:ln w="9525">{fill("<a:srgbClr val=\"1F2A44\"/>")}</a:ln>',['Rounded box'],color=white))
   +two(4,6,6,10,sp(4,'Oval','ellipse',fill(red),'<a:ln><a:noFill/></a:ln>',['Oval'],color=white))
   +two(1,7,4,11,sp(5,'Text box','rect','<a:noFill/>','<a:ln><a:noFill/></a:ln>',['Text box line 1','Text box line 2'],txbox=True)))

def png(w=60,h=40):
    raw=b''.join(b'\x00'+b''.join(bytes((int(255*x/w),int(255*y/h),180)) for x in range(w)) for y in range(h))
    def ch(t,d): c=struct.pack('>I',len(d))+t+d; return c+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+ch(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+ch(b'IDAT',zlib.compress(raw))+ch(b'IEND',b'')

theme=(f'{HDR}<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Sample"><a:themeElements><a:clrScheme name="Sample"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F2A44"/></a:dk2><a:lt2><a:srgbClr val="EEECE1"/></a:lt2>'
  '<a:accent1><a:srgbClr val="1F6F8B"/></a:accent1><a:accent2><a:srgbClr val="E4572E"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>'
  '<a:fontScheme name="Sample"><a:majorFont><a:latin typeface="Calibri Light"/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/></a:minorFont></a:fontScheme></a:themeElements></a:theme>')
styles=(f'{HDR}<styleSheet {NS}><fonts count="2"><font><sz val="11"/><color theme="1"/><name val="Calibri"/></font><font><b/><sz val="11"/><color theme="1"/><name val="Calibri"/></font></fonts>'
  '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
  '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs></styleSheet>')
ct=(f'{HDR}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/>'
  '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
  +''.join(f'<Override PartName="/xl/worksheets/sheet{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' for i in (1,2,3,4))
  +'<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>'
  +''.join(f'<Override PartName="/xl/drawings/drawing{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>' for i in (1,2,3,4))
  +''.join(f'<Override PartName="/xl/charts/chart{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>' for i in (5,6,7,8))+'</Types>')
wb=f'{HDR}<workbook {NS}><sheets><sheet name="Pie" sheetId="1" r:id="rId1"/><sheet name="Area" sheetId="2" r:id="rId2"/><sheet name="Scatter" sheetId="3" r:id="rId3"/><sheet name="Pics" sheetId="4" r:id="rId4"/></sheets></workbook>'
with zipfile.ZipFile('tools/office_selftest/sample_chart2.xlsx','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('[Content_Types].xml',ct)
    z.writestr('_rels/.rels',rels([('rId1','officeDocument','xl/workbook.xml')]))
    z.writestr('xl/workbook.xml',wb)
    z.writestr('xl/_rels/workbook.xml.rels',rels([('rId1','worksheet','worksheets/sheet1.xml'),('rId2','worksheet','worksheets/sheet2.xml'),('rId3','worksheet','worksheets/sheet3.xml'),('rId4','worksheet','worksheets/sheet4.xml'),('rId5','styles','styles.xml'),('rId6','theme','theme/theme1.xml')]))
    for i,s in enumerate((sh_pie,sh_area,sh_sc,sh_pics),1):
        z.writestr(f'xl/worksheets/sheet{i}.xml',s)
        z.writestr(f'xl/worksheets/_rels/sheet{i}.xml.rels',rels([('rId1','drawing',f'../drawings/drawing{i}.xml')]))
    for i,d in enumerate((d1,d2,d3,d4),1): z.writestr(f'xl/drawings/drawing{i}.xml',d)
    z.writestr('xl/drawings/_rels/drawing1.xml.rels',rels([('rId1','chart','../charts/chart5.xml'),('rId2','chart','../charts/chart6.xml')]))
    z.writestr('xl/drawings/_rels/drawing2.xml.rels',rels([('rId1','chart','../charts/chart7.xml')]))
    z.writestr('xl/drawings/_rels/drawing3.xml.rels',rels([('rId1','chart','../charts/chart8.xml')]))
    z.writestr('xl/drawings/_rels/drawing4.xml.rels',rels([('rId1','image','../media/image1.png')]))
    for i,c in zip((5,6,7,8),(chart5,chart6,chart7,chart8)): z.writestr(f'xl/charts/chart{i}.xml',c)
    z.writestr('xl/media/image1.png',png())
    z.writestr('xl/styles.xml',styles); z.writestr('xl/theme/theme1.xml',theme)
print('ok')
