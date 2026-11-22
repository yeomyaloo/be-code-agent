package com.example.owasp;

import org.dom4j.Document;
import org.dom4j.io.SAXReader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;

@RestController
public class XmlController {

    // 외부 엔티티를 막지 않은 파서로 사용자 XML 을 읽는다 (XXE)
    @PostMapping("/api/xml/import")
    public String importXml(@RequestBody String xml) throws Exception {
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes())).getDocumentElement().getTextContent();
    }

    @PostMapping("/api/xml/dom4j")
    public String dom4j(@RequestBody String xml) throws Exception {
        Document document = new SAXReader().read(new StringReader(xml));
        return document.getRootElement().getText();
    }
}
