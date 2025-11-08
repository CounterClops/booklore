package com.adityachandel.booklore.service.kobo;

import com.adityachandel.booklore.model.entity.BookEntity;
import freemarker.cache.ClassTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for converting CBX (Comic Book Archive) files to EPUB format for Kobo compatibility.
 * 
 * Currently supports:
 * - CBZ (ZIP-based comic archives)
 * 
 * Planned support:
 * - CBR (RAR-based comic archives) 
 * - CB7 (7-Zip-based comic archives)
 * 
 * Usage Example:
 * <pre>{@code
 * @Autowired
 * private CbxConversionService cbxConversionService;
 * 
 * public void convertComic() throws IOException, TemplateException {
 *     File cbzFile = new File("/path/to/comic.cbz");
 *     File tempDir = Files.createTempDirectory("cbx-conversion").toFile();
 *     BookEntity bookEntity = // ... get book metadata
 *     
 *     if (cbxConversionService.isSupportedCbxFormat(cbzFile.getName())) {
 *         File epubFile = cbxConversionService.convertCbxToEpub(cbzFile, tempDir, bookEntity);
 *         // Use the converted EPUB file for Kobo sync
 *     }
 * }
 * }</pre>
 * 
 * The generated EPUB follows the EPUB 3.0 standard and includes:
 * - Proper mimetype declaration
 * - META-INF/container.xml
 * - Content.opf with manifest and spine
 * - Navigation documents (toc.ncx and nav.xhtml)
 * - CSS styling for full-page image display
 * - Individual XHTML pages for each comic page/image
 */
@Slf4j
@Service
public class CbxConversionService {

    private static final String IMAGE_ROOT_PATH = "OEBPS/Images/";
    private static final String HTML_ROOT_PATH = "OEBPS/Text/";
    private static final String CONTENT_OPF_PATH = "OEBPS/content.opf";
    private static final String NAV_XHTML_PATH = "OEBPS/nav.xhtml";
    private static final String TOC_NCX_PATH = "OEBPS/toc.ncx";
    private static final String STYLESHEET_CSS_PATH = "OEBPS/Styles/stylesheet.css";
    private static final String COVER_IMAGE_PATH = "OEBPS/Images/cover.png";
    private static final String MIMETYPE_CONTENT = "application/epub+zip";
    
    private final Configuration freemarkerConfig;

    public CbxConversionService() {
        this.freemarkerConfig = initializeFreemarkerConfiguration();
    }

    /**
     * Represents the file group for each page in the EPUB
     */
    public record EpubContentFileGroup(String contentKey, String imagePath, String htmlPath) {
    }

    /**
     * Converts a CBX (CBZ) file to EPUB format for Kobo compatibility
     * 
     * @param cbxFile The source CBZ file
     * @param tempDir Temporary directory for processing
     * @param bookEntity Book metadata entity
     * @return The converted EPUB file
     * @throws IOException If file operations fail
     * @throws TemplateException If template processing fails
     */
    public File convertCbxToEpub(File cbxFile, File tempDir, BookEntity bookEntity) 
            throws IOException, TemplateException {
        validateInputs(cbxFile, tempDir);
        
        log.info("Starting CBX to EPUB conversion for: {}", cbxFile.getName());
        
        File outputFile = executeCbxConversion(cbxFile, tempDir, bookEntity);
        
        log.info("Successfully converted {} to {} (size: {} bytes)",
                cbxFile.getName(), outputFile.getName(), outputFile.length());
        return outputFile;
    }

    private File executeCbxConversion(File cbxFile, File tempDir, BookEntity bookEntity) 
            throws IOException, TemplateException {
        
        Path epubFilePath = Paths.get(tempDir.getAbsolutePath(),
                cbxFile.getName().replaceFirst("\\.[^.]+$", "") + ".epub");
        File epubFile = epubFilePath.toFile();

        List<BufferedImage> images = extractImagesFromCbx(cbxFile);
        if (images.isEmpty()) {
            throw new IllegalStateException("No valid images found in CBX file: " + cbxFile.getName());
        }

        log.debug("Extracted {} images from CBX file", images.size());

        try (ZipArchiveOutputStream zipOut = new ZipArchiveOutputStream(new FileOutputStream(epubFile))) {
            // Create EPUB structure
            addMimetypeEntry(zipOut);
            addMetaInfContainer(zipOut);
            addStylesheet(zipOut);
            
            List<EpubContentFileGroup> contentGroups = addImagesAndPages(zipOut, images);
            
            addContentOpf(zipOut, bookEntity, contentGroups);
            addTocNcx(zipOut, bookEntity, contentGroups);
            addNavXhtml(zipOut, bookEntity, contentGroups);
        }

        return epubFile;
    }

    private void validateInputs(File cbxFile, File tempDir) {
        if (cbxFile == null || !cbxFile.isFile()) {
            throw new IllegalArgumentException("Invalid CBX file: " + cbxFile);
        }

        if (!cbxFile.getName().toLowerCase().endsWith(".cbz")) {
            throw new IllegalArgumentException("Only CBZ files are currently supported: " + cbxFile.getName());
        }
        
        if (tempDir == null || !tempDir.isDirectory()) {
            throw new IllegalArgumentException("Invalid temp directory: " + tempDir);
        }
    }

    /**
     * Initialize Freemarker configuration for template processing
     */
    private Configuration initializeFreemarkerConfiguration() {
        Configuration config = new Configuration(Configuration.VERSION_2_3_33);
        config.setTemplateLoader(new ClassTemplateLoader(this.getClass(), "/templates/epub"));
        config.setDefaultEncoding(StandardCharsets.UTF_8.name());
        config.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        config.setLogTemplateExceptions(false);
        config.setWrapUncheckedExceptions(true);
        return config;
    }

    /**
     * Extract images from CBX file (currently supports CBZ format)
     */
    private List<BufferedImage> extractImagesFromCbx(File cbxFile) throws IOException {
        List<BufferedImage> images = new ArrayList<>();
        
        try (ZipFile zipFile = new ZipFile(cbxFile)) {
            List<ZipArchiveEntry> imageEntries = Collections.list(zipFile.getEntries())
                    .stream()
                    .filter(entry -> !entry.isDirectory() && isImageFile(entry.getName()))
                    .sorted(Comparator.comparing(entry -> entry.getName().toLowerCase()))
                    .collect(Collectors.toList());

            log.debug("Found {} image entries in CBX file", imageEntries.size());

            for (ZipArchiveEntry entry : imageEntries) {
                try (InputStream inputStream = zipFile.getInputStream(entry)) {
                    BufferedImage image = ImageIO.read(inputStream);
                    if (image != null) {
                        images.add(image);
                        log.debug("Successfully loaded image: {}", entry.getName());
                    } else {
                        log.warn("Failed to load image (unsupported format?): {}", entry.getName());
                    }
                } catch (Exception e) {
                    log.warn("Error reading image {}: {}", entry.getName(), e.getMessage());
                }
            }
        }
        
        return images;
    }

    /**
     * Check if file is a supported image format
     */
    private boolean isImageFile(String fileName) {
        String lowerName = fileName.toLowerCase();
        return lowerName.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp)$");
    }

    /**
     * Add mimetype entry (must be first and uncompressed)
     */
    private void addMimetypeEntry(ZipArchiveOutputStream zipOut) throws IOException {
        byte[] mimetypeBytes = MIMETYPE_CONTENT.getBytes(StandardCharsets.UTF_8);
        ZipArchiveEntry mimetypeEntry = new ZipArchiveEntry("mimetype");
        mimetypeEntry.setMethod(ZipArchiveEntry.STORED);
        mimetypeEntry.setSize(mimetypeBytes.length);
        mimetypeEntry.setCrc(calculateCrc32(mimetypeBytes));
        
        zipOut.putArchiveEntry(mimetypeEntry);
        zipOut.write(mimetypeBytes);
        zipOut.closeArchiveEntry();
    }

    /**
     * Add META-INF/container.xml
     */
    private void addMetaInfContainer(ZipArchiveOutputStream zipOut) throws IOException, TemplateException {
        Map<String, Object> model = new HashMap<>();
        model.put("contentOpfPath", CONTENT_OPF_PATH);
        
        String containerXml = processTemplate("xml/container.xml.ftl", model);
        
        ZipArchiveEntry containerEntry = new ZipArchiveEntry("META-INF/container.xml");
        zipOut.putArchiveEntry(containerEntry);
        zipOut.write(containerXml.getBytes(StandardCharsets.UTF_8));
        zipOut.closeArchiveEntry();
    }

    /**
     * Add CSS stylesheet
     */
    private void addStylesheet(ZipArchiveOutputStream zipOut) throws IOException {
        String stylesheetContent = loadResourceAsString("/templates/epub/css/stylesheet.css");
        
        ZipArchiveEntry stylesheetEntry = new ZipArchiveEntry(STYLESHEET_CSS_PATH);
        zipOut.putArchiveEntry(stylesheetEntry);
        zipOut.write(stylesheetContent.getBytes(StandardCharsets.UTF_8));
        zipOut.closeArchiveEntry();
    }

    /**
     * Add images and corresponding HTML pages
     */
    private List<EpubContentFileGroup> addImagesAndPages(ZipArchiveOutputStream zipOut, List<BufferedImage> images) 
            throws IOException, TemplateException {
        
        List<EpubContentFileGroup> contentGroups = new ArrayList<>();

        // Add cover image
        if (!images.isEmpty()) {
            addImageToZip(zipOut, COVER_IMAGE_PATH, images.get(0));
        }

        // Add each page
        for (int i = 0; i < images.size(); i++) {
            BufferedImage image = images.get(i);
            String contentKey = String.format("page-%04d", i + 1);
            String imageFileName = contentKey + ".png";
            String htmlFileName = contentKey + ".xhtml";

            String imagePath = IMAGE_ROOT_PATH + imageFileName;
            String htmlPath = HTML_ROOT_PATH + htmlFileName;

            // Add image
            addImageToZip(zipOut, imagePath, image);

            // Add HTML page
            String htmlContent = generatePageHtml(imageFileName, i + 1);
            ZipArchiveEntry htmlEntry = new ZipArchiveEntry(htmlPath);
            zipOut.putArchiveEntry(htmlEntry);
            zipOut.write(htmlContent.getBytes(StandardCharsets.UTF_8));
            zipOut.closeArchiveEntry();

            contentGroups.add(new EpubContentFileGroup(contentKey, imagePath, htmlPath));
        }

        return contentGroups;
    }

    /**
     * Add image to ZIP archive
     */
    private void addImageToZip(ZipArchiveOutputStream zipOut, String imagePath, BufferedImage image) 
            throws IOException {
        ZipArchiveEntry imageEntry = new ZipArchiveEntry(imagePath);
        zipOut.putArchiveEntry(imageEntry);
        ImageIO.write(image, "png", zipOut);
        zipOut.closeArchiveEntry();
    }

    /**
     * Generate HTML page content for an image
     */
    private String generatePageHtml(String imageFileName, int pageNumber) throws IOException, TemplateException {
        Map<String, Object> model = new HashMap<>();
        model.put("imageFileName", "../Images/" + imageFileName);
        model.put("pageNumber", pageNumber);
        model.put("stylesheetPath", "../Styles/stylesheet.css");
        
        return processTemplate("xml/image_page.xhtml.ftl", model);
    }

    /**
     * Add content.opf file
     */
    private void addContentOpf(ZipArchiveOutputStream zipOut, BookEntity bookEntity, 
                              List<EpubContentFileGroup> contentGroups) throws IOException, TemplateException {
        
        Map<String, Object> model = createBookMetadataModel(bookEntity);
        model.put("contentFileGroups", contentGroups);
        model.put("coverImagePath", COVER_IMAGE_PATH);
        model.put("tocNcxPath", TOC_NCX_PATH);
        model.put("navXhtmlPath", NAV_XHTML_PATH);
        model.put("stylesheetCssPath", STYLESHEET_CSS_PATH);
        model.put("firstPageId", contentGroups.isEmpty() ? "" : "page_" + contentGroups.get(0).contentKey());
        
        String contentOpf = processTemplate("xml/content.opf.ftl", model);
        
        ZipArchiveEntry contentEntry = new ZipArchiveEntry(CONTENT_OPF_PATH);
        zipOut.putArchiveEntry(contentEntry);
        zipOut.write(contentOpf.getBytes(StandardCharsets.UTF_8));
        zipOut.closeArchiveEntry();
    }

    /**
     * Add toc.ncx file
     */
    private void addTocNcx(ZipArchiveOutputStream zipOut, BookEntity bookEntity, 
                          List<EpubContentFileGroup> contentGroups) throws IOException, TemplateException {
        
        Map<String, Object> model = createBookMetadataModel(bookEntity);
        model.put("contentFileGroups", contentGroups);
        
        String tocNcx = processTemplate("xml/toc.xml.ftl", model);
        
        ZipArchiveEntry tocEntry = new ZipArchiveEntry(TOC_NCX_PATH);
        zipOut.putArchiveEntry(tocEntry);
        zipOut.write(tocNcx.getBytes(StandardCharsets.UTF_8));
        zipOut.closeArchiveEntry();
    }

    /**
     * Add nav.xhtml file
     */
    private void addNavXhtml(ZipArchiveOutputStream zipOut, BookEntity bookEntity, 
                            List<EpubContentFileGroup> contentGroups) throws IOException, TemplateException {
        
        Map<String, Object> model = createBookMetadataModel(bookEntity);
        model.put("contentFileGroups", contentGroups);
        
        String navXhtml = processTemplate("xml/nav.xhtml.ftl", model);
        
        ZipArchiveEntry navEntry = new ZipArchiveEntry(NAV_XHTML_PATH);
        zipOut.putArchiveEntry(navEntry);
        zipOut.write(navXhtml.getBytes(StandardCharsets.UTF_8));
        zipOut.closeArchiveEntry();
    }

    /**
     * Create metadata model for templates
     */
    private Map<String, Object> createBookMetadataModel(BookEntity bookEntity) {
        Map<String, Object> model = new HashMap<>();
        
        if (bookEntity != null && bookEntity.getMetadata() != null) {
            var metadata = bookEntity.getMetadata();
            model.put("title", metadata.getTitle() != null ? metadata.getTitle() : "Unknown Comic");
            model.put("language", metadata.getLanguage() != null ? metadata.getLanguage() : "en");
            model.put("identifier", "urn:uuid:" + UUID.randomUUID());
        } else {
            model.put("title", "Unknown Comic");
            model.put("language", "en");
            model.put("identifier", "urn:uuid:" + UUID.randomUUID());
        }
        
        model.put("modified", Instant.now().toString());
        
        return model;
    }

    /**
     * Process Freemarker template with given model
     */
    private String processTemplate(String templateName, Map<String, Object> model) 
            throws IOException, TemplateException {
        try {
            Template template = freemarkerConfig.getTemplate(templateName);
            StringWriter writer = new StringWriter();
            template.process(model, writer);
            return writer.toString();
        } catch (IOException e) {
            throw new IOException("Failed to load template: " + templateName, e);
        } catch (TemplateException e) {
            throw new TemplateException("Failed to process template: " + templateName, e, null);
        }
    }

    /**
     * Load resource file as string
     */
    private String loadResourceAsString(String resourcePath) throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Calculate CRC32 checksum for mimetype entry
     */
    private long calculateCrc32(byte[] data) {
        java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
        crc32.update(data);
        return crc32.getValue();
    }

    /**
     * Check if the service supports the given CBX file format
     * 
     * @param fileName The file name to check
     * @return true if the format is supported
     */
    public boolean isSupportedCbxFormat(String fileName) {
        if (fileName == null) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".cbz");
    }

}
