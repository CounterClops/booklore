<#ftl output_format="XML" encoding="UTF-8">
<?xml version="1.0" encoding="UTF-8"?>
<package version="3.0" unique-identifier="BookID" xmlns="http://www.idpf.org/2007/opf">
  <metadata xmlns:opf="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>${title!""}</dc:title>
    <dc:language>${language!""}</dc:language>
    <dc:identifier id="BookID">${identifier!""}</dc:identifier>
    <meta property="dcterms:modified">${modified!""}</meta>
    <meta name="cover" content="cover" />
  </metadata>

  <manifest>
    <item id="cover" href="${coverImagePath}" media-type="image/png" properties="cover-image" />
    <item id="ncx" href="${tocNcxPath}" media-type="application/x-dtbncx+xml" />
    <item id="nav" href="${navXhtmlPath}" properties="nav" media-type="application/xhtml+xml" />

    <#-- Loop over the content file groups and emit the two items per entry -->
    <#list contentFileGroups as file>
      <item id="${'page_' + file.contentKey}" href="${file.htmlPath}" media-type="application/xhtml+xml" />
      <item id="${'img_' + file.contentKey}" href="${file.imagePath}" media-type="image/png" />
    </#list>

    <item id="css" href="${stylesheetCssPath}" media-type="text/css" />
  </manifest>

  <spine page-progression-direction="ltr" toc="ncx">
    <#list contentFileGroups as file>
      <itemref idref="page_${file.contentKey}" />
    </#list>
  </spine>
</package>