package com.meeting.common;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);

    public static String extractText(Path filePath, String ext) throws IOException {
        if (".pdf".equalsIgnoreCase(ext)) {
            return extractPdfText(filePath);
        } else if (".docx".equalsIgnoreCase(ext)) {
            return extractDocxText(filePath);
        } else if (".doc".equalsIgnoreCase(ext)) {
            return extractDocText(filePath);
        }
        throw new IllegalArgumentException("Unsupported format: " + ext);
    }

    private static String extractPdfText(Path filePath) throws IOException {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private static String extractDocxText(Path filePath) throws IOException {
        try (InputStream is = Files.newInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(is);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            POIXMLProperties.CoreProperties props = doc.getProperties().getCoreProperties();
            return extractor.getText();
        }
    }

    private static String extractDocText(Path filePath) throws IOException {
        try (InputStream is = Files.newInputStream(filePath);
             HWPFDocument doc = new HWPFDocument(is)) {
            org.apache.poi.hwpf.extractor.WordExtractor extractor =
                    new org.apache.poi.hwpf.extractor.WordExtractor(doc);
            String text = extractor.getText();
            extractor.close();
            return text;
        }
    }
}
