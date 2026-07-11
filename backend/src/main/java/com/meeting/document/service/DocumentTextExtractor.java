package com.meeting.document.service;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class DocumentTextExtractor {

    // Heading style name → markdown prefix
    private static final Map<String, Integer> HEADING_STYLES = Map.ofEntries(
            Map.entry("Heading1", 1), Map.entry("Heading 1", 1),
            Map.entry("Heading2", 2), Map.entry("Heading 2", 2),
            Map.entry("Heading3", 3), Map.entry("Heading 3", 3),
            Map.entry("Heading4", 4), Map.entry("Heading 4", 4),
            Map.entry("Heading5", 5), Map.entry("Heading 5", 5),
            // Custom styles from meeting minutes templates
            Map.entry("Cap_Header", 1),
            Map.entry("Cap_Heading_1", 2),
            Map.entry("Cap_Heading_2", 3),
            Map.entry("Cap_Heading_3", 4),
            Map.entry("Cap_Heading_4", 4)
    );

    // Metadata key words to detect meeting info tables
    private static final Set<String> META_KEYS = Set.of("日期", "时间", "会议时长", "地点", "召集人", "与会人");

    // Speaker pattern: "XX总意见：" / "XX作了汇报：" / "XX总："
    // No ^ anchor — matches mid-paragraph too (table cell text has newlines removed)
    // Negative lookbehind prevents greedily absorbing preceding CJK text into the name
    private static final Pattern SPEAKER_PATTERN = Pattern.compile(
            "(?<=^|[^\\u4e00-\\u9fff])([\\u4e00-\\u9fff]{1,4}(?:总意见|作了.{0,10}?汇报|总))[：:]");

    // Section labels that are redundant when followed by a table (e.g. "会议讨论：" before content table)
    private static final Set<String> SECTION_LABELS = Set.of("会议讨论", "会议信息", "会议决策", "会议总结", "会议结论");

    public static String extractText(Path filePath, String ext) throws IOException {
        return switch (ext.toLowerCase()) {
            case ".pdf" -> extractPdfText(filePath);
            case ".docx" -> extractDocxText(filePath);
            case ".doc" -> extractDocText(filePath);
            default -> throw new IllegalArgumentException("Unsupported format: " + ext);
        };
    }

    // ==================== DOCX ====================

    private static String extractDocxText(Path filePath) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(filePath))) {
            StringBuilder sb = new StringBuilder();
            List<IBodyElement> elements = doc.getBodyElements();
            for (int i = 0; i < elements.size(); i++) {
                IBodyElement element = elements.get(i);
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph para = (XWPFParagraph) element;
                    // Skip redundant section labels that precede a table
                    // (e.g. standalone "会议讨论：" paragraph before content table)
                    if (i + 1 < elements.size() && elements.get(i + 1).getElementType() == BodyElementType.TABLE) {
                        String text = getParagraphText(para).strip()
                                .replace("：", "").replace(":", "").strip();
                        if (SECTION_LABELS.contains(text)) {
                            continue;
                        }
                    }
                    sb.append(convertParagraph(para));
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    sb.append(convertTable((XWPFTable) element));
                }
            }
            return sb.toString();
        }
    }

    private static String convertParagraph(XWPFParagraph para) {
        String styleName = para.getStyle();
        String text = getParagraphText(para).strip();
        if (text.isEmpty()) return "\n";

        Integer headingLevel = styleName != null ? HEADING_STYLES.get(styleName) : null;
        if (headingLevel != null) {
            return "#".repeat(headingLevel) + " " + text + "\n\n";
        }

        // Numbered list
        if (para.getNumFmt() != null) {
            return "- " + text + "\n";
        }

        // Inline formatting from runs
        StringBuilder line = new StringBuilder();
        for (XWPFRun run : para.getRuns()) {
            String runText = run.text();
            if (runText == null || runText.isEmpty()) continue;
            if (run.isBold() && run.isItalic()) {
                line.append("***").append(runText).append("***");
            } else if (run.isBold()) {
                line.append("**").append(runText).append("**");
            } else if (run.isItalic()) {
                line.append("*").append(runText).append("*");
            } else {
                line.append(runText);
            }
        }

        String formatted = line.toString().strip();
        if (formatted.isEmpty()) return "\n";
        return formatted + "\n\n";
    }

    private static String getParagraphText(XWPFParagraph para) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : para.getRuns()) {
            if (run.text() != null) sb.append(run.text());
        }
        return sb.toString();
    }

    private static String convertTable(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText().strip().replace("\n", " "));
            }
            rows.add(cells);
        }
        if (rows.isEmpty()) return "";

        // Detect table type
        if (isMetadataTable(rows)) {
            return convertMetadataTable(rows);
        }
        if (isContentTable(rows)) {
            return convertContentTable(rows);
        }
        return convertGenericTable(rows);
    }

    /**
     * Metadata table: 4 columns, first column contains known keys like 日期/时间/召集人/与会人
     */
    private static boolean isMetadataTable(List<List<String>> rows) {
        if (rows.isEmpty()) return false;
        int cols = rows.get(0).size();
        if (cols < 2) return false;
        // Check if first column of any row contains metadata keys
        for (List<String> row : rows) {
            if (!row.isEmpty() && META_KEYS.contains(row.get(0))) return true;
        }
        return false;
    }

    /**
     * Convert metadata table to "## 会议信息" with plain key-value pairs.
     * Handles both 2-col (key|value) and 4-col (key|value|key|value) layouts.
     */
    private static String convertMetadataTable(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("## 会议信息\n\n");
        for (List<String> row : rows) {
            if (row.size() >= 2 && !row.get(0).isBlank()) {
                sb.append("**").append(row.get(0).strip()).append("**：").append(row.get(1).strip()).append("\n");
            }
            // Handle 4-col layout: key1|val1|key2|val2
            if (row.size() >= 4 && !row.get(2).isBlank()) {
                sb.append("**").append(row.get(2).strip()).append("**：").append(row.get(3).strip()).append("\n");
            }
        }
        return sb.append("\n").toString();
    }

    /**
     * Content table: 2 columns, first row is header (议题/内容描述),
     * last row may be 会议决策
     */
    private static boolean isContentTable(List<List<String>> rows) {
        if (rows.size() < 2) return false;
        List<String> header = rows.get(0);
        if (header.size() < 2) return false;
        String first = header.get(0);
        return first.contains("议题") || first.contains("内容") || first.contains("主题");
    }

    private static String convertContentTable(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("## 会议讨论\n\n");
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() < 2) continue;
            String topic = row.get(0).strip();
            String content = row.get(1).strip();

            if (topic.equals("会议决策") || topic.contains("决策")) {
                // Extract decision content
                String decision = content;
                if (decision.startsWith("决策：") || decision.startsWith("决策:")) {
                    decision = decision.substring(3);
                }
                sb.append("## 会议决策\n\n").append(decision.strip()).append("\n\n");
                continue;
            }

            // Topic heading
            sb.append("### ").append(topic).append("\n\n");
            // Parse content: detect speaker patterns
            sb.append(parseDiscussionContent(content));
        }
        return sb.toString();
    }

    /**
     * Parse discussion content, detecting all speaker patterns (including mid-paragraph).
     * Table cell text has newlines removed, so speakers may appear anywhere in the content.
     */
    private static String parseDiscussionContent(String content) {
        StringBuilder sb = new StringBuilder();
        Matcher m = SPEAKER_PATTERN.matcher(content);
        int lastEnd = 0;

        while (m.find()) {
            // Text before this speaker
            String before = content.substring(lastEnd, m.start()).strip();
            if (!before.isEmpty()) {
                sb.append(before).append("\n\n");
            }
            // Speaker label in bold
            sb.append("**").append(m.group().strip()).append("**\n\n");
            lastEnd = m.end();
        }

        // Remaining text after last speaker
        String remaining = content.substring(lastEnd).strip();
        if (!remaining.isEmpty()) {
            sb.append(remaining).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Generic table → standard markdown pipe table
     */
    private static String convertGenericTable(List<List<String>> rows) {
        if (rows.isEmpty()) return "";
        int cols = rows.get(0).size();
        StringBuilder sb = new StringBuilder();

        // Header row
        sb.append("|");
        for (String cell : rows.get(0)) {
            sb.append(" ").append(escapePipe(cell)).append(" |");
        }
        sb.append("\n|");
        for (int c = 0; c < cols; c++) {
            sb.append("------|");
        }
        sb.append("\n");

        // Data rows
        for (int i = 1; i < rows.size(); i++) {
            sb.append("|");
            for (String cell : rows.get(i)) {
                sb.append(" ").append(escapePipe(cell)).append(" |");
            }
            sb.append("\n");
        }
        return sb.append("\n").toString();
    }

    private static String escapePipe(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }

    // ==================== PDF ====================

    private static String extractPdfText(Path filePath) throws IOException {
        try (PDDocument doc = Loader.loadPDF(filePath.toFile())) {
            // Pass 1: analyze font sizes
            FontSizeAnalyzer analyzer = new FontSizeAnalyzer();
            analyzer.getText(doc);
            float bodyFontSize = analyzer.getDominantFontSize();

            // Pass 2: extract with markdown formatting
            MarkdownPdfStripper stripper = new MarkdownPdfStripper(bodyFontSize);
            return stripper.getText(doc);
        }
    }

    /**
     * Analyzes font sizes across the document to determine the dominant (body) font size.
     */
    private static class FontSizeAnalyzer extends PDFTextStripper {
        private final Map<Integer, Long> fontSizeCounts = new HashMap<>();

        FontSizeAnalyzer() throws IOException {
            super.setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions.isEmpty()) return;
            float fontSize = positions.get(0).getFontSizeInPt();
            int rounded = Math.round(fontSize);
            fontSizeCounts.merge(rounded, 1L, Long::sum);
            super.writeString(text, positions);
        }

        float getDominantFontSize() {
            return fontSizeCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .map(Integer::floatValue)
                    .orElse(12f);
        }
    }

    /**
     * PDFTextStripper subclass that outputs markdown based on font size heuristics.
     */
    private static class MarkdownPdfStripper extends PDFTextStripper {
        private final float bodyFontSize;
        private final StringBuilder output = new StringBuilder();
        private String lastFontKey = "";
        private int consecutiveNewlines = 0;

        MarkdownPdfStripper(float bodyFontSize) throws IOException {
            this.bodyFontSize = bodyFontSize;
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions.isEmpty()) {
                super.writeString(text, positions);
                return;
            }

            TextPosition first = positions.get(0);
            float fontSize = first.getFontSizeInPt();
            boolean isBold = isBoldFont(first.getFont().getName());

            // Determine heading level by font size ratio
            String fontKey = Math.round(fontSize) + (isBold ? "B" : "N");
            if (!fontKey.equals(lastFontKey) && !lastFontKey.isEmpty()) {
                // Font change — potential heading boundary
                if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') {
                    output.append("\n");
                }
            }
            lastFontKey = fontKey;

            String line = text.strip();
            if (line.isEmpty()) return;

            if (fontSize > bodyFontSize * 1.5f) {
                output.append("## ").append(line).append("\n\n");
                consecutiveNewlines = 0;
            } else if (fontSize > bodyFontSize * 1.2f) {
                output.append("### ").append(line).append("\n\n");
                consecutiveNewlines = 0;
            } else if (isBold && fontSize > bodyFontSize * 1.05f) {
                output.append("**").append(line).append("**\n\n");
                consecutiveNewlines = 0;
            } else {
                output.append(line).append("\n");
                consecutiveNewlines = 0;
            }

            super.writeString(text, positions);
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            consecutiveNewlines++;
            if (consecutiveNewlines <= 2) {
                output.append("\n");
            }
            super.writeLineSeparator();
        }

        @Override
        public String getText(PDDocument doc) throws IOException {
            output.setLength(0);
            super.getText(doc);
            return output.toString();
        }

        private boolean isBoldFont(String fontName) {
            if (fontName == null) return false;
            String lower = fontName.toLowerCase();
            return lower.contains("bold") || lower.contains("black") || lower.contains("heavy");
        }
    }

    // ==================== DOC ====================

    private static String extractDocText(Path filePath) throws IOException {
        try (HWPFDocument doc = new HWPFDocument(Files.newInputStream(filePath))) {
            Range range = doc.getRange();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < range.numParagraphs(); i++) {
                org.apache.poi.hwpf.usermodel.Paragraph para = range.getParagraph(i);
                String text = para.text().stripTrailing();
                if (text.isEmpty()) {
                    sb.append("\n");
                    continue;
                }

                // Style index: 0=Normal, 1=Heading1, 2=Heading2, etc.
                int styleIndex = para.getStyleIndex();
                if (styleIndex >= 1 && styleIndex <= 5) {
                    sb.append("#".repeat(styleIndex)).append(" ").append(text).append("\n\n");
                } else {
                    sb.append(text).append("\n");
                }
            }
            return sb.toString();
        }
    }
}
