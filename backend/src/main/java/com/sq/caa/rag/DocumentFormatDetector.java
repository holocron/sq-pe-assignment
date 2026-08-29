package com.sq.caa.rag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides what an uploaded file really is, from its bytes.
 *
 * <p>The file name is never trusted: {@code policy.pdf} that is actually a renamed spreadsheet, an
 * executable or a legacy {@code .doc} must be refused, because the parsers downstream would either
 * fail obscurely or - worse - index garbage that the risk agent would later cite as policy.
 *
 * <p>Detection is structural rather than a magic-number guess:
 * <ul>
 *   <li><b>PDF</b> - the {@code %PDF-} signature, searched in the first kilobyte because the
 *       specification tolerates leading bytes before the header;</li>
 *   <li><b>DOCX</b> - a readable ZIP container that actually holds {@code word/document.xml}. This
 *       is what separates a {@code .docx} from the {@code .xlsx}, {@code .pptx}, {@code .odt} and
 *       plain {@code .zip} files that share the same first four bytes.</li>
 * </ul>
 *
 * <p>When the content is valid but disagrees with the extension the upload is still refused, since
 * that is almost always a mistake on the operator's side and silently accepting it would produce a
 * knowledge base whose file names lie about their contents.
 */
@Component
public class DocumentFormatDetector {

    private static final Logger log = LoggerFactory.getLogger(DocumentFormatDetector.class);

    /** {@code %PDF-} may be preceded by junk; the reference implementations scan this far. */
    private static final int PDF_HEADER_SEARCH_WINDOW = 1024;

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_LOCAL_FILE_HEADER = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] ZIP_EMPTY_ARCHIVE = {0x50, 0x4B, 0x05, 0x06};
    private static final byte[] ZIP_SPANNED_ARCHIVE = {0x50, 0x4B, 0x07, 0x08};

    /** Compound File Binary Format: the legacy {@code .doc} / {@code .xls} / {@code .ppt} container. */
    private static final byte[] OLE2_SIGNATURE =
            {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private static final byte[] RTF_SIGNATURE = "{\\rtf".getBytes(StandardCharsets.US_ASCII);

    /** The part every WordprocessingML package must contain. */
    private static final String DOCX_MAIN_PART = "word/document.xml";

    private static final String XLSX_MAIN_PART = "xl/workbook.xml";
    private static final String PPTX_MAIN_PART = "ppt/presentation.xml";
    private static final String ODF_MIMETYPE_PART = "mimetype";

    /** Cheap guard against a zip bomb disguised as a document. */
    private static final int MAX_SCANNED_ZIP_ENTRIES = 2048;

    /**
     * Identifies the format of an upload.
     *
     * @param filename the original file name, used only to catch extension/content mismatches
     * @param content  the complete file bytes
     * @return the detected format, never null
     * @throws UnsupportedDocumentException when the bytes are not a {@code .docx} or a {@code .pdf},
     *                                      or when they contradict the file name
     */
    public KnowledgeFormat detect(String filename, byte[] content) {
        if (content == null || content.length == 0) {
            throw new UnsupportedDocumentException(filename, "empty file",
                    "The uploaded file is empty.");
        }

        KnowledgeFormat actual = sniff(filename, content);
        KnowledgeFormat claimed = KnowledgeFormat.fromFilename(filename);
        if (claimed != null && claimed != actual) {
            throw new UnsupportedDocumentException(filename, actual.name(),
                    "The file content is a " + actual.extension().toUpperCase(Locale.ROOT)
                            + " document but the file is named '" + filename + "'. Rename it to ."
                            + actual.extension() + " and upload it again.");
        }
        log.debug("Upload '{}' detected as {}", filename, actual);
        return actual;
    }

    private KnowledgeFormat sniff(String filename, byte[] content) {
        if (containsPdfHeader(content)) {
            return KnowledgeFormat.PDF;
        }
        if (startsWith(content, OLE2_SIGNATURE)) {
            throw new UnsupportedDocumentException(filename, "legacy Microsoft Office (OLE2) file",
                    "Legacy .doc files are not supported. Save the document as .docx and upload it "
                            + "again.");
        }
        if (startsWith(content, RTF_SIGNATURE)) {
            throw new UnsupportedDocumentException(filename, "RTF document",
                    "RTF documents are not supported. Only " + KnowledgeFormat.acceptedDescription()
                            + " files can be indexed.");
        }
        if (isZipContainer(content)) {
            return classifyZip(filename, content);
        }
        throw new UnsupportedDocumentException(filename, describeUnknown(content),
                "Only " + KnowledgeFormat.acceptedDescription() + " files can be indexed. The "
                        + "uploaded file is " + describeUnknown(content) + ".");
    }

    /** Walks the ZIP directory far enough to tell a {@code .docx} from its OOXML siblings. */
    private KnowledgeFormat classifyZip(String filename, byte[] content) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            String foreignPart = null;
            ZipEntry entry;
            int scanned = 0;
            while ((entry = zip.getNextEntry()) != null && scanned++ < MAX_SCANNED_ZIP_ENTRIES) {
                String name = entry.getName();
                if (DOCX_MAIN_PART.equalsIgnoreCase(name)) {
                    return KnowledgeFormat.DOCX;
                }
                if (foreignPart == null) {
                    foreignPart = foreignPartLabel(name);
                }
            }
            String detected = foreignPart != null ? foreignPart : "ZIP archive";
            throw new UnsupportedDocumentException(filename, detected,
                    "The file is a " + detected + ", not a Word document. Only "
                            + KnowledgeFormat.acceptedDescription() + " files can be indexed.");
        } catch (IOException e) {
            throw new UnsupportedDocumentException(filename, "unreadable ZIP archive",
                    "The file looks like a .docx but its ZIP container could not be read: "
                            + e.getMessage());
        }
    }

    private static String foreignPartLabel(String entryName) {
        if (XLSX_MAIN_PART.equalsIgnoreCase(entryName)) {
            return "Excel workbook";
        }
        if (PPTX_MAIN_PART.equalsIgnoreCase(entryName)) {
            return "PowerPoint presentation";
        }
        if (ODF_MIMETYPE_PART.equals(entryName)) {
            return "OpenDocument file";
        }
        return null;
    }

    private static boolean containsPdfHeader(byte[] content) {
        int limit = Math.min(content.length, PDF_HEADER_SEARCH_WINDOW);
        for (int offset = 0; offset + PDF_SIGNATURE.length <= limit; offset++) {
            if (matchesAt(content, offset, PDF_SIGNATURE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isZipContainer(byte[] content) {
        return startsWith(content, ZIP_LOCAL_FILE_HEADER)
                || startsWith(content, ZIP_EMPTY_ARCHIVE)
                || startsWith(content, ZIP_SPANNED_ARCHIVE);
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        return content.length >= signature.length && matchesAt(content, 0, signature);
    }

    private static boolean matchesAt(byte[] content, int offset, byte[] signature) {
        for (int i = 0; i < signature.length; i++) {
            if (content[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /** Best-effort label for the rejection message so the operator knows what they actually sent. */
    private static String describeUnknown(byte[] content) {
        if (looksLikeText(content)) {
            return "a plain text file";
        }
        return "a binary file of an unrecognised type";
    }

    private static boolean looksLikeText(byte[] content) {
        int limit = Math.min(content.length, 512);
        for (int i = 0; i < limit; i++) {
            int b = content[i] & 0xFF;
            boolean printable = b >= 0x20 || b == '\n' || b == '\r' || b == '\t';
            if (!printable) {
                return false;
            }
        }
        return true;
    }
}
