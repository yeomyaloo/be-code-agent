package com.example.shop.importer;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.List;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    @PostMapping("/cart")
    @SuppressWarnings("unchecked")
    public int cart(@RequestBody byte[] body) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(body))) {
            List<String> items = (List<String>) in.readObject();
            return items.size();
        }
    }

    @PostMapping("/feed")
    public String feed(@RequestBody String xml) throws Exception {
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document document = builder.parse(new ByteArrayInputStream(xml.getBytes()));
        return document.getDocumentElement().getAttribute("title");
    }

    @PostMapping("/catalog")
    public String catalog(@RequestBody String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new ByteArrayInputStream(xml.getBytes()));
        return document.getDocumentElement().getAttribute("name");
    }
}
