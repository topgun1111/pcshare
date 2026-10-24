# Part 6: builds sample_float.docx (floating picture, text box, rectangle, behind-text picture). Needs Pillow. Run in tools/office_selftest.
import zipfile, io
from PIL import Image, ImageDraw
def png(w,h,col,txt):
    im=Image.new('RGB',(w,h),col); d=ImageDraw.Draw(im); d.rectangle([0,0,w-1,h-1],outline=(0,0,0),width=3); d.text((10,10),txt,fill=(0,0,0)); b=io.BytesIO(); im.save(b,'PNG'); return b.getvalue()
pic=png(200,150,(255,200,120),'SQUARE-RIGHT')
wm=png(400,200,(210,210,255),'WATERMARK')
NS='xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup" mc:Ignorable=""'
def picxml(rid,cx,cy,anchor,name):
    g=f'<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="1" name="{name}"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="{rid}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>'
    return f'<w:r><w:drawing>{anchor}<wp:extent cx="{cx}" cy="{cy}"/><wp:docPr id="1" name="{name}" descr="{name}"/>{g}</wp:anchor></w:drawing></w:r>'
def anc(behind,distl=114300): return f'<wp:anchor distT="0" distB="0" distL="{distl}" distR="114300" simplePos="0" relativeHeight="251658240" behindDoc="{1 if behind else 0}" locked="0" layoutInCell="1" allowOverlap="1">'
p_pic = picxml('rId10',1905000,1428750, anc(False)+'<wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="margin"><wp:align>right</wp:align></wp:positionH><wp:positionV relativeFrom="paragraph"><wp:posOffset>0</wp:posOffset></wp:positionV>'.replace('</wp:positionV>','</wp:positionV>')+'<wp:extent cx="1905000" cy="1428750"/><wp:wrapSquare wrapText="bothSides"/>','x') if False else ''
def pic_anchor(rid,cx,cy,behind,posh,posv,wrap,name):
    a=anc(behind)+'<wp:simplePos x="0" y="0"/>'+posh+posv+f'<wp:extent cx="{cx}" cy="{cy}"/><wp:effectExtent l="0" t="0" r="0" b="0"/>'+wrap+f'<wp:docPr id="{abs(hash(name))%1000}" name="{name}" descr="{name}"/>'
    g=f'<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="1" name="{name}"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="{rid}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>'
    return f'<w:r><w:drawing>{a}{g}</wp:anchor></w:drawing></w:r>'
def wsp_anchor(cx,cy,posh,posv,wrap,spPr,txbx,name):
    a=anc(False)+'<wp:simplePos x="0" y="0"/>'+posh+posv+f'<wp:extent cx="{cx}" cy="{cy}"/><wp:effectExtent l="0" t="0" r="0" b="0"/>'+wrap+f'<wp:docPr id="{abs(hash(name))%1000+5}" name="{name}"/><wp:cNvGraphicFramePr/>'
    g=f'<a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"><wps:wsp><wps:cNvSpPr/>{spPr}{txbx}<wps:bodyPr rot="0" lIns="91440" tIns="45720" rIns="91440" bIns="45720" anchor="t"/></wps:wsp></a:graphicData></a:graphic>'
    return f'<w:r><mc:AlternateContent><mc:Choice Requires="wps"><w:drawing>{a}{g}</wp:anchor></w:drawing></mc:Choice><mc:Fallback><w:pict/></mc:Fallback></mc:AlternateContent></w:r>'
H=lambda rel,x: f'<wp:positionH relativeFrom="{rel}">{x}</wp:positionH>'
V=lambda rel,x: f'<wp:positionV relativeFrom="{rel}">{x}</wp:positionV>'
LOREM='Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. '
def para(t,extra=''): return f'<w:p>{extra}<w:r><w:t xml:space="preserve">{t}</w:t></w:r></w:p>'
tb_sp='<wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="2286000" cy="1000000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="FFF2CC"/></a:solidFill><a:ln w="19050"><a:solidFill><a:srgbClr val="BF9000"/></a:solidFill></a:ln></wps:spPr>'
tb_tx='<wps:txbx><w:txbxContent><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>TEXTBOX TITLE</w:t></w:r></w:p><w:p><w:r><w:t>Text inside a floating text box with two paragraphs.</w:t></w:r></w:p></w:txbxContent></wps:txbx>'
rc_sp='<wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="3200000" cy="500000"/></a:xfrm><a:prstGeom prst="roundRect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="C00000"/></a:solidFill><a:ln w="12700"><a:solidFill><a:srgbClr val="000000"/></a:solidFill></a:ln></wps:spPr>'
body=''
body+=para('Heading line for the floating object test')
body+=f'<w:p>{pic_anchor("rId10",1905000,1428750,False,H("margin","<wp:align>right</wp:align>"),V("paragraph","<wp:posOffset>0</wp:posOffset>"),"<wp:wrapSquare wrapText=\"bothSides\"/>","SQUARE-RIGHT")}<w:r><w:t xml:space="preserve">{LOREM*3}</w:t></w:r></w:p>'
body+=f'<w:p>{wsp_anchor(2286000,1000000,H("margin","<wp:posOffset>0</wp:posOffset>"),V("paragraph","<wp:posOffset>0</wp:posOffset>"),"<wp:wrapSquare wrapText=\"bothSides\"/>",tb_sp,tb_tx,"TEXTBOX")}<w:r><w:t xml:space="preserve">{LOREM*3}</w:t></w:r></w:p>'
body+=f'<w:p>{wsp_anchor(3200000,500000,H("margin","<wp:align>center</wp:align>"),V("paragraph","<wp:posOffset>0</wp:posOffset>"),"<wp:wrapTopAndBottom/>",rc_sp,"","RECT")}<w:r><w:t xml:space="preserve">Paragraph with a top-and-bottom rectangle above. {LOREM}</w:t></w:r></w:p>'
body+=f'<w:p>{pic_anchor("rId11",3657600,1828800,True,H("page","<wp:align>center</wp:align>"),V("page","<wp:align>center</wp:align>"),"<wp:wrapNone/>","WATERMARK")}<w:r><w:t xml:space="preserve">Paragraph with a behind-text watermark picture centred on the page. {LOREM*2}</w:t></w:r></w:p>'
body+=para(LOREM*4)
body+='<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1417" w:bottom="1417" w:left="1417" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>'
doc=f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document {NS}><w:body>{body}</w:body></w:document>'
ct='<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>'
rels='<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>'
drels='<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId10" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/><Relationship Id="rId11" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image2.png"/></Relationships>'
with zipfile.ZipFile('sample_float.docx','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('[Content_Types].xml',ct); z.writestr('_rels/.rels',rels); z.writestr('word/document.xml',doc); z.writestr('word/_rels/document.xml.rels',drels)
    z.writestr('word/media/image1.png',pic); z.writestr('word/media/image2.png',wm)
open('pic.png','wb').write(pic); open('wm.png','wb').write(wm)
print('ok')
