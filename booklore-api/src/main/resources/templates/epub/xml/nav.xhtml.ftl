<#ftl output_format="XML" encoding="UTF-8">
<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head>
    <title>Navigation</title>
    <meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
</head>
<body>
    <nav epub:type="toc" id="toc">
        <h1>Table of Contents</h1>
        <ol>
            <li>
                <#assign firstPageHref = contentFileGroups?has_content?then(
                    contentFileGroups[0].htmlPath?replace('OEBPS/', ''), 
                    'Text/page-0001.xhtml'
                )>
                <a href="${firstPageHref}">${title!'Unknown Comic'}</a>
            </li>
        </ol>
    </nav>
</body>
</html>