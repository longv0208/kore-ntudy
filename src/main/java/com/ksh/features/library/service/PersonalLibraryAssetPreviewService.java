package com.ksh.features.library.service;

import com.ksh.features.library.dto.LibraryDtos.LibraryAssetDetail;
import com.ksh.features.library.dto.LibraryDtos.LibraryAssetPreview;
import com.ksh.features.storage.ObjectStorage;
import com.ksh.features.storage.StoredObject;
import jakarta.persistence.EntityNotFoundException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;

/**
 * Builds bounded, read-only previews for owner-private assets. PDF, image and
 * video stay browser-native; OOXML and plain-text files are converted to
 * escaped display data without ever executing macros or active content.
 */
@Service
public class PersonalLibraryAssetPreviewService {

    private static final Logger log =
            LoggerFactory.getLogger(PersonalLibraryAssetPreviewService.class);
    private static final long MAX_PARSED_BYTES = 10L * 1024L * 1024L;
    private static final long MAX_OOXML_EXPANDED_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_TABLE_ROWS = 40;
    private static final int MAX_TABLE_COLUMNS = 16;
    private static final int MAX_TEXT_BLOCKS = 120;
    private static final int MAX_CELL_LENGTH = 500;

    private final LibraryService libraryService;
    private final ObjectStorage objectStorage;

    public PersonalLibraryAssetPreviewService(LibraryService libraryService,
                                              ObjectStorage objectStorage) {
        this.libraryService = libraryService;
        this.objectStorage = objectStorage;
    }

    public LibraryAssetPreview load(Long ownerId, Long assetId) {
        LibraryAssetDetail detail = libraryService.detail(ownerId, assetId);
        String previewKind = classify(detail);
        if ("PDF".equals(previewKind) || "IMAGE".equals(previewKind)
                || "VIDEO".equals(previewKind)) {
            return model(detail, previewKind, null, List.of(), List.of(), null);
        }
        if ("UNSUPPORTED".equals(previewKind)) {
            return model(detail, previewKind, null, List.of(), List.of(),
                    "Định dạng này chưa hỗ trợ xem trực tiếp. Bạn vẫn có thể tải tệp xuống.");
        }
        var handle = libraryService.contentHandle(ownerId, assetId);
        try {
            if (!objectStorage.exists(handle.storageKey())) {
                throw new EntityNotFoundException("Không tìm thấy nội dung tài liệu");
            }
            try (StoredObject object = objectStorage.open(handle.storageKey())) {
                long storedLength = object.contentLength();
                if (storedLength > MAX_PARSED_BYTES) {
                    return model(detail, previewKind, null, List.of(), List.of(),
                            "Tệp quá lớn để tạo bản xem trước an toàn. Vui lòng tải xuống để xem đầy đủ.");
                }
                if (storedLength >= 0 && storedLength != detail.sizeBytes()) {
                    return model(detail, previewKind, null, List.of(), List.of(),
                            "Kích thước tệp đã thay đổi nên không thể tạo bản xem trước an toàn.");
                }
                InputStream bounded = new PreviewBoundedInputStream(
                        object.inputStream(), MAX_PARSED_BYTES);
                return switch (previewKind) {
                    case "SPREADSHEET" -> spreadsheet(detail, safeOoxmlInput(bounded));
                    case "DOCUMENT" -> document(detail, safeOoxmlInput(bounded));
                    case "PRESENTATION" -> presentation(detail, safeOoxmlInput(bounded));
                    case "TEXT" -> text(detail, bounded);
                    default -> model(detail, "UNSUPPORTED", null, List.of(), List.of(),
                            "Định dạng này chưa hỗ trợ xem trực tiếp.");
                };
            }
        } catch (EntityNotFoundException ex) {
            throw ex;
        } catch (PreviewLimitExceededException ex) {
            return model(detail, previewKind, null, List.of(), List.of(),
                    "Tệp quá lớn để tạo bản xem trước an toàn. Vui lòng tải xuống để xem đầy đủ.");
        } catch (IOException | RuntimeException ex) {
            log.warn("Could not build safe preview for personal asset {}", assetId, ex);
            return model(detail, previewKind, null, List.of(), List.of(),
                    "Không thể tạo bản xem trước cho tệp này. Tệp gốc vẫn có thể tải xuống.");
        }
    }

    private static LibraryAssetPreview spreadsheet(LibraryAssetDetail detail,
                                                   InputStream input) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        String sheetName = null;
        try (Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() > 0) {
                Sheet sheet = workbook.getSheetAt(0);
                sheetName = sheet.getSheetName();
                DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("vi-VN"));
                FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
                int lastRow = Math.min(sheet.getLastRowNum(), MAX_TABLE_ROWS - 1);
                for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= lastRow; rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    int lastCell = row == null ? 0
                            : Math.min(Math.max(row.getLastCellNum(), 0), MAX_TABLE_COLUMNS);
                    List<String> cells = new ArrayList<>();
                    for (int cellIndex = 0; cellIndex < lastCell; cellIndex++) {
                        Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        String value = cell == null ? "" : formatter.formatCellValue(cell, evaluator);
                        cells.add(limit(value));
                    }
                    if (!cells.isEmpty()) rows.add(List.copyOf(cells));
                }
            }
        }
        String message = rows.isEmpty() ? "Trang tính đầu tiên không có dữ liệu để hiển thị." : null;
        return model(detail, "SPREADSHEET", sheetName, List.copyOf(rows), List.of(), message);
    }

    private static LibraryAssetPreview document(LibraryAssetDetail detail,
                                                InputStream input) throws IOException {
        List<String> blocks = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(input)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                addBlock(blocks, paragraph.getText());
                if (blocks.size() >= MAX_TEXT_BLOCKS) break;
            }
            if (blocks.size() < MAX_TEXT_BLOCKS) {
                for (XWPFTable table : document.getTables()) {
                    table.getRows().forEach(row -> addBlock(blocks,
                            row.getTableCells().stream()
                                    .map(cell -> cell.getText() == null ? "" : cell.getText().trim())
                                    .reduce((left, right) -> left + " | " + right).orElse("")));
                    if (blocks.size() >= MAX_TEXT_BLOCKS) break;
                }
            }
        }
        String message = blocks.isEmpty() ? "Tài liệu không có đoạn văn để hiển thị." : null;
        return model(detail, "DOCUMENT", null, List.of(), List.copyOf(blocks), message);
    }

    private static LibraryAssetPreview presentation(LibraryAssetDetail detail,
                                                    InputStream input) throws IOException {
        List<String> blocks = new ArrayList<>();
        try (XMLSlideShow slideShow = new XMLSlideShow(input)) {
            for (int index = 0; index < slideShow.getSlides().size()
                    && blocks.size() < MAX_TEXT_BLOCKS; index++) {
                List<String> slideText = new ArrayList<>();
                for (XSLFShape shape : slideShow.getSlides().get(index).getShapes()) {
                    if (shape instanceof XSLFTextShape textShape
                            && textShape.getText() != null && !textShape.getText().isBlank()) {
                        slideText.add(textShape.getText().trim());
                    }
                }
                if (!slideText.isEmpty()) {
                    addBlock(blocks, "Trang " + (index + 1) + " — " + String.join(" · ", slideText));
                }
            }
        }
        String message = blocks.isEmpty() ? "Bản trình chiếu không có văn bản để hiển thị." : null;
        return model(detail, "PRESENTATION", null, List.of(), List.copyOf(blocks), message);
    }

    private static LibraryAssetPreview text(LibraryAssetDetail detail,
                                            InputStream input) throws IOException {
        List<String> blocks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while (blocks.size() < MAX_TEXT_BLOCKS && (line = reader.readLine()) != null) {
                addBlock(blocks, line);
            }
        }
        String message = blocks.isEmpty() ? "Tệp không có nội dung để hiển thị." : null;
        return model(detail, "TEXT", null, List.of(), List.copyOf(blocks), message);
    }

    private static void addBlock(List<String> blocks, String value) {
        if (blocks.size() >= MAX_TEXT_BLOCKS || value == null || value.isBlank()) return;
        blocks.add(limit(value.trim()));
    }

    private static String limit(String value) {
        if (value == null) return "";
        String normalized = value.replace('\u0000', ' ').trim();
        return normalized.length() <= MAX_CELL_LENGTH ? normalized
                : normalized.substring(0, MAX_CELL_LENGTH) + "…";
    }

    /**
     * Read a bounded OOXML payload once, then cap its total decompressed ZIP
     * entries before Apache POI sees it. This avoids accepting a small
     * compressed zip bomb whose expanded XML would otherwise consume memory.
     */
    private static InputStream safeOoxmlInput(InputStream input) throws IOException {
        byte[] bytes = readPreviewBytes(input);
        long expanded = 0L;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            while (zip.getNextEntry() != null) {
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    expanded += read;
                    if (expanded > MAX_OOXML_EXPANDED_BYTES) {
                        throw new PreviewLimitExceededException();
                    }
                }
            }
        }
        return new ByteArrayInputStream(bytes);
    }

    private static byte[] readPreviewBytes(InputStream input) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > MAX_PARSED_BYTES) {
                    throw new PreviewLimitExceededException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    /**
     * Guards unknown-length streams as well as storage implementations that
     * accidentally report a smaller object. At the byte limit, one extra byte
     * is probed: a clean EOF is accepted, while further data aborts parsing.
     */
    private static final class PreviewBoundedInputStream extends FilterInputStream {
        private long remaining;
        private boolean endVerified;

        private PreviewBoundedInputStream(InputStream input, long maxBytes) {
            super(input);
            this.remaining = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (remaining > 0) {
                int value = in.read();
                if (value >= 0) remaining--;
                return value;
            }
            return verifyEndOfStream();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) return verifyEndOfStream();
            int read = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }

        @Override
        public long skip(long length) throws IOException {
            if (length <= 0 || remaining == 0) {
                if (remaining == 0) verifyEndOfStream();
                return 0;
            }
            long skipped = in.skip(Math.min(length, remaining));
            if (skipped > 0) remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), remaining);
        }

        private int verifyEndOfStream() throws IOException {
            if (!endVerified) {
                endVerified = true;
                if (in.read() >= 0) {
                    throw new PreviewLimitExceededException();
                }
            }
            return -1;
        }
    }

    private static final class PreviewLimitExceededException extends IOException {
    }

    private static String classify(LibraryAssetDetail detail) {
        String mime = detail.mimeType() == null ? ""
                : detail.mimeType().toLowerCase(Locale.ROOT);
        String format = detail.formatClass() == null ? ""
                : detail.formatClass().toLowerCase(Locale.ROOT);
        String label = detail.formatLabel() == null ? ""
                : detail.formatLabel().toUpperCase(Locale.ROOT);
        if ("application/pdf".equals(mime) || "pdf".equals(format)) return "PDF";
        if (mime.startsWith("image/") || "image".equals(format)) return "IMAGE";
        if (mime.startsWith("video/") || "video".equals(format)) return "VIDEO";
        if ("excel".equals(format) && !"CSV".equals(label)) return "SPREADSHEET";
        if ("word".equals(format) && "DOCX".equals(label)) return "DOCUMENT";
        if ("powerpoint".equals(format) && "PPTX".equals(label)) return "PRESENTATION";
        if (mime.startsWith("text/") || "CSV".equals(label) || "TXT".equals(label)) {
            return "TEXT";
        }
        return "UNSUPPORTED";
    }

    private static LibraryAssetPreview model(LibraryAssetDetail detail,
                                             String previewKind,
                                             String sheetName,
                                             List<List<String>> rows,
                                             List<String> blocks,
                                             String message) {
        return new LibraryAssetPreview(
                detail.id(), detail.title(), detail.originalFilename(), detail.mimeType(),
                detail.formatLabel(), detail.formatClass(), previewKind,
                detail.contentUrl(), detail.downloadUrl(), sheetName,
                rows, blocks, message);
    }
}
