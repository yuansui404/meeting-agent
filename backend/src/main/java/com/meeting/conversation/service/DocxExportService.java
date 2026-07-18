package com.meeting.conversation.service;

import com.meeting.template.service.TemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocxExportService {

    private final TemplateService templateService;

    /**
     * 将改写内容填入模板，生成 docx 文件。
     *
     * @param content    改写后的内容
     * @param templateId 模板 ID
     * @return 生成的 docx 文件路径
     */
    public Path export(String content, Long templateId) {
        Path templatePath = templateService.getTemplatePath(templateId);
        Path outputPath;
        try {
            outputPath = Files.createTempFile("meeting-minutes-", ".docx");
        } catch (IOException e) {
            log.error("Failed to create temp file", e);
            throw new RuntimeException("临时文件创建失败", e);
        }

        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(templatePath))) {
            // 替换段落文本
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            boolean found = false;
            for (XWPFParagraph para : paragraphs) {
                List<XWPFRun> runs = para.getRuns();
                if (runs != null) {
                    for (XWPFRun run : runs) {
                        if (run.text() != null && run.text().contains("{{content}}")) {
                            run.setText(run.text().replace("{{content}}", content), 0);
                            found = true;
                        }
                    }
                }
            }

            // 替换表格中的文本
            List<XWPFTable> tables = doc.getTables();
            for (XWPFTable table : tables) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String cellText = cell.getText();
                        if (cellText != null && cellText.contains("{{content}}")) {
                            // 清空原有内容并设置新内容
                            for (int i = 0; i < cell.getParagraphs().size(); i++) {
                                cell.removeParagraph(i);
                            }
                            XWPFParagraph newPara = cell.addParagraph();
                            XWPFRun newRun = newPara.createRun();
                            newRun.setText(cellText.replace("{{content}}", content));
                            found = true;
                        }
                    }
                }
            }

            // 如果模板中没有 {{content}} 占位符，在文档末尾追加内容
            if (!found) {
                XWPFParagraph newPara = doc.createParagraph();
                XWPFRun newRun = newPara.createRun();
                newRun.setText(content);
            }

            // 写入输出文件
            try (FileOutputStream out = new FileOutputStream(outputPath.toFile())) {
                doc.write(out);
            }

            log.info("Docx exported to: {}", outputPath);
            return outputPath;

        } catch (IOException e) {
            log.error("Failed to export docx", e);
            throw new RuntimeException("文档导出失败", e);
        }
    }
}