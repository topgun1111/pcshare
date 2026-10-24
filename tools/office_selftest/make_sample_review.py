# Part 8: builds sample_review.docx (2 comments, 1 tracked insertion + 1 tracked deletion inside a sentence, one fully inserted and one fully deleted
# paragraph, a TOC-style list with internal links (w:hyperlink w:anchor and a complex HYPERLINK \l field), two bookmarked headings). Pure stdlib.
# Expected FINAL text of paragraph 3: "The quick brown fox jumps quickly across the yard."  ORIGINAL: "The quick brown fox jumps lazily across the yard."
import zipfile
W='xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
def r(t): return f'<w:r><w:t xml:space="preserve">{t}</w:t></w:r>'
ins=lambda a,i,body: f'<w:ins w:id="{i}" w:author="{a}" w:date="2026-10-20T10:00:00Z">{body}</w:ins>'
dele=lambda a,i,t: f'<w:del w:id="{i}" w:author="{a}" w:date="2026-10-20T10:05:00Z"><w:r><w:delText xml:space="preserve">{t}</w:delText></w:r></w:del>'
def cref(i): return f'<w:r><w:rPr><w:rStyle w:val="CommentReference"/></w:rPr><w:commentReference w:id="{i}"/></w:r>'
body=''
body+='<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:bookmarkStart w:id="1" w:name="_Toc100001"/>'+r('Review sample')+'<w:bookmarkEnd w:id="1"/></w:p>'
body+='<w:p><w:hyperlink w:anchor="_Toc100002" w:history="1">'+r('Second part')+'<w:r><w:tab/></w:r>'+r('2')+'</w:hyperlink></w:p>'
body+=('<w:p>'+r('The quick brown fox ')+'<w:commentRangeStart w:id="0"/>'+r('jumps')+'<w:commentRangeEnd w:id="0"/>'+cref(0)
       +ins('Alice',10,r(' quickly'))+dele('Bob',11,' lazily')+r(' across the ')
       +'<w:commentRangeStart w:id="1"/>'+r('yard')+'<w:commentRangeEnd w:id="1"/>'+cref(1)+r('.')+'</w:p>')
body+='<w:p>'+ins('Alice',12,r('This whole paragraph was added.'))+'</w:p>'
body+='<w:p>'+dele('Bob',13,'This whole paragraph was removed.')+'</w:p>'
body+=('<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:bookmarkStart w:id="2" w:name="_Toc100002"/>'+r('Second part')+'<w:bookmarkEnd w:id="2"/></w:p>')
body+=('<w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> HYPERLINK \\l "_Toc100001" </w:instrText></w:r>'
       '<w:r><w:fldChar w:fldCharType="separate"/></w:r>'+r('Back to the first part')+'<w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>')
body+='<w:p>'+r('Plain closing paragraph.')+'</w:p>'
doc=f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document {W}><w:body>{body}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1417" w:bottom="1417" w:left="1417" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>'
def cm(i,a,ini,t): return f'<w:comment w:id="{i}" w:author="{a}" w:date="2026-10-21T09:30:00Z" w:initials="{ini}"><w:p><w:r><w:annotationRef/></w:r><w:r><w:t xml:space="preserve">{t}</w:t></w:r></w:p></w:comment>'
comments=f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:comments {W}>'+cm(0,'Carol','C','Is "jumps" the right verb here?')+cm(1,'Dave','D','Which yard? Please be specific.')+'</w:comments>'
styles=f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles {W}><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style></w:styles>'
ct='<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/comments.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/></Types>'
rels='<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>'
drels='<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="comments.xml"/></Relationships>'
with zipfile.ZipFile('sample_review.docx','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('[Content_Types].xml',ct); z.writestr('_rels/.rels',rels); z.writestr('word/document.xml',doc)
    z.writestr('word/_rels/document.xml.rels',drels); z.writestr('word/styles.xml',styles); z.writestr('word/comments.xml',comments)
print('sample_review.docx written')
