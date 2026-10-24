# Part 7: builds sample_styles.docx (custom theme, theme-coloured heading with border, banded table style with first row / first column / last row,
# direct cell shading + borders, TOC-style dotted leader lines, three-part tab line, default-stop tabs). Pure stdlib. Run in tools/office_selftest.
import zipfile
W='xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
theme='''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="LSTest"><a:themeElements>
<a:clrScheme name="LS"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="2B2D42"/></a:dk2><a:lt2><a:srgbClr val="EDF2F4"/></a:lt2>
<a:accent1><a:srgbClr val="1F6F8B"/></a:accent1><a:accent2><a:srgbClr val="E4572E"/></a:accent2><a:accent3><a:srgbClr val="76B041"/></a:accent3><a:accent4><a:srgbClr val="FFC914"/></a:accent4><a:accent5><a:srgbClr val="8D5B9E"/></a:accent5><a:accent6><a:srgbClr val="17BEBB"/></a:accent6>
<a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>
<a:fontScheme name="LS"><a:majorFont><a:latin typeface="Cambria"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme></a:themeElements></a:theme>'''
styles=f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles {W}>
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:asciiTheme="minorHAnsi" w:hAnsiTheme="minorHAnsi"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:pBdr><w:bottom w:val="single" w:sz="12" w:space="1" w:color="E4572E" w:themeColor="accent2"/></w:pBdr><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:rFonts w:asciiTheme="majorHAnsi" w:hAnsiTheme="majorHAnsi"/><w:b/><w:color w:val="175368" w:themeColor="accent1" w:themeShade="BF"/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="TOC1"><w:name w:val="toc 1"/><w:basedOn w:val="Normal"/><w:pPr><w:tabs><w:tab w:val="right" w:leader="dot" w:pos="9350"/></w:tabs><w:spacing w:after="60"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Header"><w:name w:val="header"/><w:basedOn w:val="Normal"/><w:pPr><w:tabs><w:tab w:val="center" w:pos="4680"/><w:tab w:val="right" w:pos="9360"/></w:tabs></w:pPr></w:style>
<w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:tblPr><w:tblInd w:w="0" w:type="dxa"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>
<w:style w:type="table" w:styleId="LSBanded"><w:name w:val="LS Banded"/><w:basedOn w:val="TableNormal"/><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr>
<w:tblPr><w:tblStyleRowBandSize w:val="1"/><w:tblBorders><w:top w:val="single" w:sz="12" w:space="0" w:color="1F6F8B" w:themeColor="accent1"/><w:bottom w:val="single" w:sz="12" w:space="0" w:color="1F6F8B" w:themeColor="accent1"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/></w:tblBorders><w:tblCellMar><w:top w:w="40" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="40" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr>
<w:tblStylePr w:type="firstRow"><w:rPr><w:b/><w:color w:val="FFFFFF" w:themeColor="background1"/></w:rPr><w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="1F6F8B" w:themeFill="accent1"/></w:tcPr></w:tblStylePr>
<w:tblStylePr w:type="lastRow"><w:rPr><w:b/></w:rPr><w:tcPr><w:tcBorders><w:top w:val="double" w:sz="6" w:space="0" w:color="auto"/></w:tcBorders></w:tcPr></w:tblStylePr>
<w:tblStylePr w:type="firstCol"><w:rPr><w:b/><w:color w:val="E4572E" w:themeColor="accent2"/></w:rPr></w:tblStylePr>
<w:tblStylePr w:type="band1Horz"><w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="C7E7F3" w:themeFill="accent1" w:themeFillTint="33"/></w:tcPr></w:tblStylePr></w:style>
</w:styles>'''
def p(t,style=None,extra=''):
    ps=f'<w:pStyle w:val="{style}"/>' if style else ''
    return f'<w:p><w:pPr>{ps}{extra}</w:pPr><w:r><w:t xml:space="preserve">{t}</w:t></w:r></w:p>'
def tabp(parts,style=None,extra=''):
    ps=f'<w:pStyle w:val="{style}"/>' if style else ''
    rs='<w:r><w:tab/></w:r>'.join(f'<w:r><w:t xml:space="preserve">{t}</w:t></w:r>' for t in parts)
    return f'<w:p><w:pPr>{ps}{extra}</w:pPr>{rs}</w:p>'
def tc(t,w,extra='',span=None):
    sp=f'<w:gridSpan w:val="{span}"/>' if span else ''
    return f'<w:tc><w:tcPr><w:tcW w:w="{w}" w:type="dxa"/>{sp}{extra}</w:tcPr><w:p><w:r><w:t>{t}</w:t></w:r></w:p></w:tc>'
rows=[('Item','Qty','Price'),('Apples','4','1.20'),('Bread','1','2.50'),('Cheese','2','7.80'),('Milk','6','0.99'),('Total','13','12.49')]
tbl='<w:tbl><w:tblPr><w:tblStyle w:val="LSBanded"/><w:tblW w:w="9360" w:type="dxa"/><w:tblLook w:val="04E0" w:firstRow="1" w:lastRow="1" w:firstColumn="1" w:lastColumn="0" w:noHBand="0" w:noVBand="1"/></w:tblPr><w:tblGrid><w:gridCol w:w="3600"/><w:gridCol w:w="2160"/><w:gridCol w:w="3600"/></w:tblGrid>'
for i,r in enumerate(rows):
    cells=''
    for j,t in enumerate(r):
        ex=''
        if i==2 and j==2: ex='<w:shd w:val="clear" w:color="auto" w:fill="FFF2CC"/><w:tcBorders><w:left w:val="single" w:sz="18" w:space="0" w:color="C00000"/></w:tcBorders>'
        if i==3 and j==1: ex='<w:vAlign w:val="center"/>'
        cells+=tc(t,[3600,2160,3600][j],ex)
    tbl+=f'<w:tr>{cells}</w:tr>'
tbl+='</w:tbl>'
body=''
body+=p('Styles and theme test','Heading1')
body+=f'<w:p><w:r><w:rPr><w:color w:val="58B8DA" w:themeColor="accent1" w:themeTint="99"/></w:rPr><w:t>Theme accent 1 at tint 99 (lighter 40%). </w:t></w:r><w:r><w:rPr><w:b/><w:color w:val="588431" w:themeColor="accent3" w:themeShade="BF"/></w:rPr><w:t>Accent 3 shade BF.</w:t></w:r></w:p>'
body+=tbl
body+=p('Contents','Heading1')
body+=tabp(['Introduction','1'],'TOC1')+tabp(['A much longer chapter title that goes on and on','12'],'TOC1')+tabp(['Appendix','103'],'TOC1')
body+=p('Header-style line:',None)
body+=tabp(['Left','Centre','Right'],'Header')
body+=tabp(['Name:','Tuna'])+tabp(['Role:','English teacher'])
body+=p('Shaded paragraph with a box border',None,'<w:pBdr><w:top w:val="single" w:sz="8" w:space="1" w:color="E4572E"/><w:bottom w:val="single" w:sz="8" w:space="1" w:color="E4572E"/></w:pBdr><w:shd w:val="clear" w:color="auto" w:fill="EDF2F4"/>')
body+='<w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr>'
doc=f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document {W}><w:body>{body}</w:body></w:document>'
ct='<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/></Types>'
rels='<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>'
drels='<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/></Relationships>'
with zipfile.ZipFile('sample_styles.docx','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('[Content_Types].xml',ct); z.writestr('_rels/.rels',rels); z.writestr('word/document.xml',doc); z.writestr('word/_rels/document.xml.rels',drels)
    z.writestr('word/styles.xml',styles); z.writestr('word/theme/theme1.xml',theme)
print('ok')
