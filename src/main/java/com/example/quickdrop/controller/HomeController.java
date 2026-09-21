package com.example.quickdrop.controller;

import com.example.quickdrop.events.FileEventService;
import com.example.quickdrop.network.NetworkAddressService;
import com.example.quickdrop.qr.QrCodeService;
import com.example.quickdrop.receive.ReceiveLinkService;
import com.example.quickdrop.share.ShareLinkService;
import com.google.zxing.WriterException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
public class HomeController {

    private static final long MAX_FILE_SIZE_BYTES =
            3L * 1024 * 1024 * 1024;

    private static final long MAX_TOTAL_STORAGE_BYTES =
            5L * 1024 * 1024 * 1024;

    private static final DateTimeFormatter EXPIRATION_FORMATTER =
            DateTimeFormatter
                    .ofPattern("MMM d, yyyy 'at' h:mm a")
                    .withZone(ZoneId.systemDefault());

    private final Path uploadDirectory =
            Path.of("uploads").toAbsolutePath().normalize();
    private final Path uploadStagingDirectory =
            uploadDirectory.resolve(".staging");

    private final ShareLinkService shareLinkService;
    private final ReceiveLinkService receiveLinkService;
    private final FileEventService fileEventService;
    private final NetworkAddressService networkAddressService;
    private final QrCodeService qrCodeService;
    private final OwnerAccessService ownerAccessService;
    private final OwnerPinService ownerPinService;
    private final int serverPort;

    public HomeController(
            ShareLinkService shareLinkService,
            ReceiveLinkService receiveLinkService,
            FileEventService fileEventService,
            NetworkAddressService networkAddressService,
            QrCodeService qrCodeService,
            OwnerAccessService ownerAccessService,
            OwnerPinService ownerPinService,
            @Value("${server.port:8080}") int serverPort
    ) {
        this.shareLinkService = shareLinkService;
        this.receiveLinkService = receiveLinkService;
        this.fileEventService = fileEventService;
        this.networkAddressService = networkAddressService;
        this.qrCodeService = qrCodeService;
        this.ownerAccessService = ownerAccessService;
        this.ownerPinService = ownerPinService;
        this.serverPort = serverPort;
    }

    @GetMapping("/")
    public synchronized String home(
            Model model,
            HttpServletRequest request
    ) throws IOException {
        if (ownerAccessService.isLocalRequest(request)
                && !ownerPinService.isConfigured()) {
            return "redirect:/owner/setup";
        }

        FilesSnapshot snapshot = createFilesSnapshot();
        String shareBaseUrl = createShareBaseUrl();
        ReceiveLinkService.LinkDetails receivingSession =
                receiveLinkService.findCurrentActive().orElse(null);
        String senderUrl = receivingSession == null
                ? null
                : shareBaseUrl + "/receive/" + receivingSession.token();

        model.addAttribute("files", snapshot.files());
        model.addAttribute("storageLabel", snapshot.storageLabel());
        model.addAttribute("shareBaseUrl", shareBaseUrl);
        model.addAttribute("senderUrl", senderUrl);
        model.addAttribute("receivingActive", receivingSession != null);
        model.addAttribute(
                "receivingExpirationLabel",
                receivingSession == null
                        ? null
                        : formatExpiration(receivingSession.expiresAt())
        );
        model.addAttribute(
                "remoteOwnerSession",
                ownerAccessService.isRemoteAuthenticated(request)
        );
        model.addAttribute(
                "localOwner",
                ownerAccessService.isLocalRequest(request)
        );

        return "index";
    }

    @GetMapping(value = "/events/files",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter fileEvents() {
        return fileEventService.subscribe();
    }

    @GetMapping("/api/files")
    public synchronized ResponseEntity<FilesSnapshot> filesSnapshot()
            throws IOException {
        return ResponseEntity.ok(createFilesSnapshot());
    }

    @GetMapping(value = "/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> createQrCode(
            @RequestParam String text
    ) throws WriterException, IOException {
        if (text.isBlank() || text.length() > 2_048) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(qrCodeService.createPng(text));
    }

    @PostMapping("/receive-links")
    public ResponseEntity<ReceiveLinkResult> startReceiving(
            @RequestBody ReceiveLinkRequest request
    ) {
        Duration lifetime;

        try {
            lifetime = calculateReceiveLifetime(request.expiration());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(new ReceiveLinkResult(
                    "Choose a valid receiving time.",
                    null,
                    null
            ));
        }

        ReceiveLinkService.LinkDetails link =
                receiveLinkService.start(lifetime);

        return ResponseEntity.ok(new ReceiveLinkResult(
                "Receiving started.",
                "/receive/" + link.token(),
                formatExpiration(link.expiresAt())
        ));
    }

    @DeleteMapping("/receive-links")
    public ResponseEntity<ReceiveLinkStatus> stopReceiving() {
        if (!receiveLinkService.stop()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ReceiveLinkStatus(
                            "Receiving is already stopped."
                    ));
        }

        return ResponseEntity.ok(new ReceiveLinkStatus(
                "Receiving stopped."
        ));
    }

    @GetMapping("/receive/{token}")
    public String showReceivePage(
            @PathVariable String token,
            Model model,
            HttpServletResponse response
    ) {
        ReceiveLinkService.LinkDetails link = receiveLinkService
                .findActive(token)
                .orElse(null);

        model.addAttribute("receiveToken", token);
        model.addAttribute("senderLabel", "Quick Drop sender");
        model.addAttribute("senderHeading", "Send files to Quick Drop");
        model.addAttribute(
                "senderDescription",
                "This page can only send files. It cannot view, download, "
                        + "share, or delete anything already stored."
        );
        model.addAttribute(
                "uploadAction",
                "/receive/" + token + "/upload"
        );

        if (link == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            model.addAttribute("active", false);
            model.addAttribute(
                    "inactiveHeading",
                    "Quick Drop is not receiving files"
            );
            model.addAttribute(
                    "inactiveDescription",
                    "This receiving session ended or expired. Ask the "
                            + "owner to start receiving again."
            );
            return "receive";
        }

        model.addAttribute("active", true);
        model.addAttribute(
                "expirationLabel",
                formatExpiration(link.expiresAt())
        );

        return "receive";
    }

    @GetMapping("/send")
    public String showRetiredSenderPage(
            Model model,
            HttpServletResponse response
    ) {
        response.setStatus(HttpServletResponse.SC_GONE);
        model.addAttribute("active", false);
        model.addAttribute("senderLabel", "Sender link replaced");
        model.addAttribute("senderHeading", "Ask for the current QR code");
        model.addAttribute(
                "senderDescription",
                "Quick Drop now uses a new link each time receiving starts."
        );
        model.addAttribute(
                "inactiveHeading",
                "This old sender address cannot accept files"
        );
        model.addAttribute(
                "inactiveDescription",
                "Ask the owner to start receiving and scan the new QR code."
        );
        return "receive";
    }

    @PostMapping("/send/upload")
    public synchronized ResponseEntity<ReceiveUploadResult> senderUpload(
            @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new ReceiveUploadResult(
                        "This old sender address is inactive. Scan the "
                                + "current QR code.",
                        null
                ));
    }

    @PostMapping("/receive/{token}/upload")
    public synchronized ResponseEntity<ReceiveUploadResult> receiveUpload(
            @PathVariable String token,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        if (receiveLinkService.findActive(token).isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(new ReceiveUploadResult(
                            "This receive link has expired or is invalid.",
                            null
                    ));
        }

        return storeReceivedUpload(file);
    }

    private ResponseEntity<ReceiveUploadResult> storeReceivedUpload(
            MultipartFile file
    ) throws IOException {
        ResponseEntity<UploadResult> uploadResult = storeUpload(
                file,
                "24h",
                false,
                true
        );
        UploadResult result = uploadResult.getBody();

        if (result == null) {
            return ResponseEntity.status(uploadResult.getStatusCode())
                    .body(new ReceiveUploadResult(
                            "The file could not be received.",
                            null
                    ));
        }

        String message = uploadResult.getStatusCode().is2xxSuccessful()
                ? result.filename() + " received successfully."
                : result.message();

        if (uploadResult.getStatusCode().is2xxSuccessful()) {
            fileEventService.fileReceived(result.filename());
        }

        return ResponseEntity.status(uploadResult.getStatusCode())
                .body(new ReceiveUploadResult(message, result.filename()));
    }

    @PostMapping("/upload")
    public synchronized ResponseEntity<UploadResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "24h") String expiration,
            @RequestParam(defaultValue = "false") boolean overwrite
    ) throws IOException {
        ResponseEntity<UploadResult> uploadResult = storeUpload(
                file,
                expiration,
                overwrite,
                false
        );
        UploadResult result = uploadResult.getBody();

        if (uploadResult.getStatusCode().is2xxSuccessful()
                && result != null) {
            fileEventService.fileUploaded(result.filename());
        }

        return uploadResult;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<UploadError> handleUploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new UploadError(
                        "Upload rejected: files must be 3 GB or smaller."
                ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<UploadError> handleUploadConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new UploadError(
                        "That filename changed during the upload. "
                                + "Please try it again."
                ));
    }

    private ResponseEntity<UploadResult> storeUpload(
            MultipartFile file,
            String expiration,
            boolean overwrite,
            boolean renameDuplicate
    ) throws IOException {
        if (file.isEmpty()
                || file.getOriginalFilename() == null
                || file.getOriginalFilename().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(UploadResult.error("Please select a file."));
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(UploadResult.error(
                            "Upload rejected: files must be 3 GB or smaller."
                    ));
        }

        Files.createDirectories(uploadDirectory);
        shareLinkService.deleteExpiredFiles();

        String filename;

        try {
            filename = Path.of(file.getOriginalFilename())
                    .getFileName()
                    .toString();
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest()
                    .body(UploadResult.error("Invalid filename."));
        }

        Path destination = uploadDirectory.resolve(filename).normalize();

        if (!destination.startsWith(uploadDirectory)) {
            return ResponseEntity.badRequest()
                    .body(UploadResult.error("Invalid filename."));
        }

        if (renameDuplicate && Files.exists(destination)) {
            filename = findAvailableFilename(filename);
            destination = uploadDirectory.resolve(filename).normalize();
        }

        if (Files.isRegularFile(destination) && !overwrite) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(UploadResult.duplicate(filename));
        }

        long existingSize = Files.isRegularFile(destination)
                ? Files.size(destination)
                : 0;
        long projectedStorage = calculateUsedStorage()
                - existingSize
                + file.getSize();

        if (projectedStorage > MAX_TOTAL_STORAGE_BYTES) {
            return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE)
                    .body(UploadResult.error(
                            "Upload rejected: Quick Drop's 5 GB storage "
                                    + "limit would be exceeded."
                    ));
        }

        Instant expiresAt;

        try {
            expiresAt = calculateExpiration(expiration);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(UploadResult.error(
                            "Choose a valid expiration time."
                    ));
        }

        replaceFileFromUpload(file, destination);

        ShareLinkService.LinkDetails shareLink =
                shareLinkService.createOrUpdateLink(filename, expiresAt);
        String expirationLabel = formatExpiration(expiresAt);

        return ResponseEntity.ok(
                new UploadResult(
                        filename + " uploaded successfully!",
                        filename,
                        "/share/" + shareLink.token(),
                        shareLink.token(),
                        expirationLabel,
                        formatStorage(projectedStorage),
                        false
                )
        );
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(
            @RequestParam("filename") String filename
    ) throws IOException {

        return createDownloadResponse(filename);
    }

    @PostMapping(value = "/download/selected",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void downloadSelected(
            @RequestParam(name = "tokens", required = false) List<String> tokens,
            HttpServletResponse response
    ) throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        if (tokens == null || tokens.isEmpty()
                || tokens.stream().anyMatch(token -> token == null || token.isBlank())) {
            downloadError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Select at least one file to download.");
            return;
        }

        List<String> filenames = new ArrayList<>();
        for (String token : new LinkedHashSet<>(tokens)) {
            String filename = shareLinkService.findFilename(token).orElse(null);
            Path filePath = filename == null
                    ? null : uploadDirectory.resolve(filename).normalize();

            if (filePath == null || !uploadDirectory.equals(filePath.getParent())
                    || !filename.equals(filePath.getFileName().toString())
                    || !Files.isRegularFile(filePath)) {
                downloadError(response, HttpServletResponse.SC_CONFLICT,
                        "A selected file expired or is no longer available. "
                                + "Refresh the file list and try again.");
                return;
            }
            filenames.add(filename);
        }

        boolean singleFile = filenames.size() == 1;
        String downloadName = singleFile ? filenames.getFirst() : "quick-drop-files.zip";
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition
                .attachment()
                .filename(downloadName, StandardCharsets.UTF_8)
                .build().toString());

        // Stream directly to the browser without buffering or an async timeout.
        if (singleFile) {
            Path filePath = uploadDirectory.resolve(downloadName);
            response.setContentType(detectMediaType(filePath).toString());
            response.setContentLengthLong(Files.size(filePath));
            Files.copy(filePath, response.getOutputStream());
        } else {
            response.setContentType("application/zip");
            writeZip(response.getOutputStream(), filenames, true);
        }
    }

    private void downloadError(
            HttpServletResponse response,
            int status,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(message);
    }

    @GetMapping("/share/{token}")
    public ResponseEntity<Resource> downloadSharedFile(
            @PathVariable String token
    ) throws IOException {

        String filename = shareLinkService.findFilename(token)
                .orElse(null);

        if (filename == null) {
            return ResponseEntity.notFound().build();
        }

        return createDownloadResponse(filename);
    }

    @PostMapping("/share/bundles")
    public ResponseEntity<BundleResult> createShareBundle(
            @RequestBody ShareRequest request
    ) {
        if (request.tokens() == null || request.tokens().size() < 2) {
            return ResponseEntity.badRequest().body(new BundleResult(
                    "Select at least two files for a combined link.",
                    null,
                    0
            ));
        }

        return shareLinkService.createBundle(request.tokens())
                .map(bundle -> ResponseEntity.ok(new BundleResult(
                        "Combined link created.",
                        "/share/bundle/" + bundle.token(),
                        bundle.fileCount()
                )))
                .orElseGet(() -> ResponseEntity.badRequest().body(
                        new BundleResult(
                                "At least two selected files must still "
                                        + "be available.",
                                null,
                                0
                        )
                ));
    }

    @GetMapping("/share/bundle/{token}")
    public ResponseEntity<StreamingResponseBody> downloadShareBundle(
            @PathVariable String token
    ) {
        List<String> filenames = shareLinkService
                .findBundleFilenames(token);

        if (filenames.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        StreamingResponseBody zipBody = outputStream ->
                writeZip(outputStream, filenames);

        String disposition = ContentDisposition
                .attachment()
                .filename("quick-drop-files.zip")
                .build()
                .toString();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .body(zipBody);
    }

    @DeleteMapping("/files")
    public ResponseEntity<DeleteResult> deleteFiles(
            @RequestBody DeleteRequest request
    ) throws IOException {

        if (request.tokens() == null || request.tokens().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new DeleteResult(
                            "Select at least one file.",
                            formatStorage(calculateUsedStorage()),
                            List.of()
                    ));
        }

        List<String> deletedTokens = shareLinkService
                .deleteByTokens(request.tokens());
        int deletedCount = deletedTokens.size();
        String noun = deletedCount == 1 ? "file" : "files";

        return ResponseEntity.ok(new DeleteResult(
                deletedCount + " " + noun + " deleted.",
                formatStorage(calculateUsedStorage()),
                deletedTokens
        ));
    }

    private ResponseEntity<Resource> createDownloadResponse(
            String filename
    ) throws IOException {

        String safeFilename;

        try {
            safeFilename = Path.of(filename)
                    .getFileName()
                    .toString();
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest().build();
        }

        Path filePath = uploadDirectory
                .resolve(safeFilename)
                .normalize();

        if (!filePath.startsWith(uploadDirectory)
                || !Files.isRegularFile(filePath)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new UrlResource(filePath.toUri());
        MediaType mediaType = detectMediaType(filePath);

        String disposition = ContentDisposition
                .attachment()
                .filename(safeFilename, StandardCharsets.UTF_8)
                .build()
                .toString();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(mediaType)
                .contentLength(Files.size(filePath))
                .body(resource);
    }

    private MediaType detectMediaType(Path filePath) throws IOException {
        String detectedType = Files.probeContentType(filePath);

        if (detectedType == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }

        try {
            return MediaType.parseMediaType(detectedType);
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private void replaceFileFromUpload(
            MultipartFile file,
            Path destination
    ) throws IOException {
        Files.createDirectories(uploadStagingDirectory);
        Path stagedFile = Files.createTempFile(
                uploadStagingDirectory,
                "upload-",
                ".part"
        );

        try {
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(
                        inputStream,
                        stagedFile,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            try {
                Files.move(
                        stagedFile,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        stagedFile,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(stagedFile);
        }
    }

    private void writeZip(
            OutputStream outputStream,
            List<String> filenames
    ) throws IOException {
        writeZip(outputStream, filenames, false);
    }

    private void writeZip(
            OutputStream outputStream,
            List<String> filenames,
            boolean requireAllFiles
    ) throws IOException {
        try (ZipOutputStream zipOutputStream =
                     new ZipOutputStream(outputStream)) {
            for (String filename : filenames) {
                Path filePath = uploadDirectory
                        .resolve(filename)
                        .normalize();

                if (!filePath.startsWith(uploadDirectory)
                        || !Files.isRegularFile(filePath)) {
                    if (requireAllFiles) {
                        throw new IOException("A selected file became unavailable.");
                    }
                    continue;
                }

                zipOutputStream.putNextEntry(new ZipEntry(filename));
                Files.copy(filePath, zipOutputStream);
                zipOutputStream.closeEntry();
            }
        }
    }

    private FileView createFileView(String filename) {
        ShareLinkService.LinkDetails shareLink =
                shareLinkService.getOrCreateLink(filename);

        return new FileView(
                filename,
                shareLink.token(),
                formatExpiration(shareLink.expiresAt())
        );
    }

    private FilesSnapshot createFilesSnapshot() throws IOException {
        Files.createDirectories(uploadDirectory);
        shareLinkService.deleteExpiredFiles();

        List<FileView> files;

        try (Stream<Path> paths = Files.list(uploadDirectory)) {
            files = paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .map(this::createFileView)
                    .toList();
        }

        return new FilesSnapshot(
                files,
                formatStorage(calculateUsedStorage())
        );
    }

    private Instant calculateExpiration(String expiration) {
        return switch (expiration) {
            case "1h" -> Instant.now().plus(Duration.ofHours(1));
            case "24h" -> Instant.now().plus(Duration.ofHours(24));
            case "7d" -> Instant.now().plus(Duration.ofDays(7));
            case "never" -> null;
            default -> throw new IllegalArgumentException(
                    "Unknown expiration value"
            );
        };
    }

    private Duration calculateReceiveLifetime(String expiration) {
        return switch (expiration) {
            case "15m" -> Duration.ofMinutes(15);
            case "1h" -> Duration.ofHours(1);
            case "24h" -> Duration.ofHours(24);
            default -> throw new IllegalArgumentException(
                    "Unknown receive-link expiration value"
            );
        };
    }

    private String findAvailableFilename(String filename) {
        int extensionStart = filename.lastIndexOf('.');
        boolean hasExtension = extensionStart > 0
                && extensionStart < filename.length() - 1;
        String baseName = hasExtension
                ? filename.substring(0, extensionStart)
                : filename;
        String extension = hasExtension
                ? filename.substring(extensionStart)
                : "";

        for (int copyNumber = 1; ; copyNumber++) {
            String candidate = baseName
                    + " ("
                    + copyNumber
                    + ")"
                    + extension;
            Path candidatePath = uploadDirectory
                    .resolve(candidate)
                    .normalize();

            if (candidatePath.startsWith(uploadDirectory)
                    && !Files.exists(candidatePath)) {
                return candidate;
            }
        }
    }

    private String formatExpiration(Instant expiresAt) {
        if (expiresAt == null) {
            return "Never expires";
        }

        return "Expires " + EXPIRATION_FORMATTER.format(expiresAt);
    }

    private long calculateUsedStorage() throws IOException {
        try (Stream<Path> paths = Files.list(uploadDirectory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException exception) {
                            throw new StorageReadException(exception);
                        }
                    })
                    .sum();
        } catch (StorageReadException exception) {
            throw (IOException) exception.getCause();
        }
    }

    private String formatStorage(long bytes) {
        double megabytes = bytes / (1024.0 * 1024.0);

        return String.format("%.1f MB of 5 GB used", megabytes);
    }

    private String createShareBaseUrl() {
        return "http://"
                + networkAddressService.findLocalIpv4Address()
                + ":"
                + serverPort;
    }

    public record UploadResult(
            String message,
            String filename,
            String sharePath,
            String shareToken,
            String expirationLabel,
            String storageLabel,
            boolean requiresOverwrite
    ) {

        public static UploadResult error(String message) {
            return new UploadResult(
                    message, null, null, null, null, null, false
            );
        }

        public static UploadResult duplicate(String filename) {
            return new UploadResult(
                    "A file named " + filename + " already exists.",
                    filename,
                    null,
                    null,
                    null,
                    null,
                    true
            );
        }
    }

    public record DeleteRequest(List<String> tokens) {
    }

    public record ShareRequest(List<String> tokens) {
    }

    public record ReceiveLinkRequest(String expiration) {
    }

    public record ReceiveLinkResult(
            String message,
            String receivePath,
            String expirationLabel
    ) {
    }

    public record ReceiveLinkStatus(String message) {
    }

    public record ReceiveUploadResult(String message, String filename) {
    }

    public record UploadError(String message) {
    }

    public record BundleResult(
            String message,
            String sharePath,
            int fileCount
    ) {
    }

    public record DeleteResult(
            String message,
            String storageLabel,
            List<String> deletedTokens
    ) {
    }

    public record FileView(
            String filename,
            String shareToken,
            String expirationLabel
    ) {
    }

    public record FilesSnapshot(
            List<FileView> files,
            String storageLabel
    ) {
    }

    private static class StorageReadException extends RuntimeException {

        private StorageReadException(IOException cause) {
            super(cause);
        }
    }
}
