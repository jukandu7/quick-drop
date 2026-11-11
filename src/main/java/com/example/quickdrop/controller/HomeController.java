package com.example.quickdrop.controller;

import com.example.quickdrop.events.FileEventService;
import com.example.quickdrop.network.PublicUrlService;
import com.example.quickdrop.qr.QrCodeService;
import com.example.quickdrop.receive.ReceiveLinkService;
import com.example.quickdrop.share.ShareLinkService;
import com.example.quickdrop.share.ShareLinkService.FileReference;
import com.google.zxing.WriterException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
public class HomeController {

    private static final DateTimeFormatter EXPIRATION_FORMATTER =
            DateTimeFormatter
                    .ofPattern("MMM d, yyyy 'at' h:mm a")
                    .withZone(ZoneId.systemDefault());

    private final ShareLinkService shareLinkService;
    private final ReceiveLinkService receiveLinkService;
    private final FileEventService fileEventService;
    private final PublicUrlService publicUrls;
    private final QrCodeService qrCodeService;
    private final OwnerAccessService ownerAccessService;
    private final OwnerPinService ownerPinService;

    public HomeController(
            ShareLinkService shareLinkService,
            ReceiveLinkService receiveLinkService,
            FileEventService fileEventService,
            PublicUrlService publicUrls,
            QrCodeService qrCodeService,
            OwnerAccessService ownerAccessService,
            OwnerPinService ownerPinService
    ) {
        this.shareLinkService = shareLinkService;
        this.receiveLinkService = receiveLinkService;
        this.fileEventService = fileEventService;
        this.publicUrls = publicUrls;
        this.qrCodeService = qrCodeService;
        this.ownerAccessService = ownerAccessService;
        this.ownerPinService = ownerPinService;
    }

    @GetMapping("/")
    public String home(
            Model model,
            HttpServletRequest request
    ) throws IOException {
        if (ownerAccessService.isLocalRequest(request)
                && !ownerPinService.isConfigured()) {
            return "redirect:/owner/setup";
        }

        FilesSnapshot snapshot = createFilesSnapshot();
        String shareBaseUrl = publicUrls.currentBaseUrl();
        ReceiveLinkService.LinkDetails receivingSession =
                receiveLinkService.findCurrentActive().orElse(null);
        String senderUrl = receivingSession == null || shareBaseUrl == null
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
    public SseEmitter fileEvents(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return fileEventService.subscribe(ownerAccessService.liveAccess(request));
    }

    @GetMapping("/api/files")
    public ResponseEntity<FilesSnapshot> filesSnapshot()
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
    public ResponseEntity<ReceiveUploadResult> senderUpload() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new ReceiveUploadResult(
                        "This old sender address is inactive. Scan the "
                                + "current QR code.",
                        null
                ));
    }

    @PostMapping("/receive/{token}/upload")
    public ResponseEntity<ReceiveUploadResult> receiveUpload(
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
    public ResponseEntity<UploadResult> upload(
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
            MultipartFile file, String expiration, boolean overwrite, boolean renameDuplicate
    ) throws IOException {
        Instant expiresAt;
        try {
            expiresAt = calculateExpiration(expiration);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(UploadResult.error("Choose a valid expiration time."));
        }
        try {
            ShareLinkService.StoredUpload stored = shareLinkService.storeUpload(file, expiresAt, overwrite, renameDuplicate);
            return ResponseEntity.ok(new UploadResult(stored.filename() + " uploaded successfully!",
                    stored.filename(), "/share/" + stored.token(), stored.token(),
                    formatExpiration(stored.expiresAt()), formatStorage(stored.usedBytes()), false));
        } catch (ShareLinkService.UploadRejected rejection) {
            UploadResult result = rejection.duplicateFilename() == null
                    ? UploadResult.error(rejection.getMessage())
                    : UploadResult.duplicate(rejection.duplicateFilename());
            return ResponseEntity.status(rejection.status()).body(result);
        }
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(
            @RequestParam("filename") String filename
    ) throws IOException {

        FileReference file = shareLinkService.findByFilename(filename)
                .orElse(null);
        return file == null ? ResponseEntity.notFound().build() : createDownloadResponse(file);
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

        List<FileReference> files = new ArrayList<>();
        for (String token : new LinkedHashSet<>(tokens)) {
            FileReference file = shareLinkService.findFile(token).orElse(null);
            Path filePath = file == null ? null : file.path();
            if (filePath == null || !filePath.normalize().startsWith(shareLinkService.storageDirectory())
                    || !file.filename().equals(Path.of(file.filename()).getFileName().toString())
                    || !Files.isRegularFile(filePath)) {
                downloadError(response, HttpServletResponse.SC_CONFLICT,
                        "A selected file expired or is no longer available. "
                                + "Refresh the file list and try again.");
                return;
            }
            files.add(file);
        }

        boolean singleFile = files.size() == 1;
        String downloadName = singleFile ? files.getFirst().filename() : "quick-drop-files.zip";
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition
                .attachment()
                .filename(downloadName, StandardCharsets.UTF_8)
                .build().toString());

        // Stream directly to the browser without buffering or an async timeout.
        if (singleFile) {
            Path filePath = files.getFirst().path();
            response.setContentType(detectMediaType(filePath).toString());
            response.setContentLengthLong(Files.size(filePath));
            Files.copy(filePath, response.getOutputStream());
        } else {
            response.setContentType("application/zip");
            writeZip(response.getOutputStream(), files, true);
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

        FileReference file = shareLinkService.findFile(token)
                .orElse(null);

        if (file == null) {
            return ResponseEntity.notFound().build();
        }

        return file == null ? ResponseEntity.notFound().build() : createDownloadResponse(file);
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
        List<FileReference> files = shareLinkService
                .findBundleFiles(token);

        if (files.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        StreamingResponseBody zipBody = outputStream ->
                writeZip(outputStream, files);

        String disposition = ContentDisposition
                .attachment()
                .filename("quick-drop-files.zip")
                .build()
                .toString();

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .body(zipBody);
    }

    @DeleteMapping("/share-links")
    public ResponseEntity<RevokeResult> revokeShareLinks(@RequestBody ShareRequest request) {
        if (request.tokens() == null || request.tokens().isEmpty() || request.tokens().size() > 1000
                || request.tokens().stream().anyMatch(token -> token == null || token.isBlank())) {
            return ResponseEntity.badRequest().body(new RevokeResult("Select between 1 and 1000 files.", List.of()));
        }
        List<String> revoked = shareLinkService.revokeLinks(request.tokens());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new RevokeResult("Old download links revoked for " + revoked.size()
                        + " file(s). Files kept; create new links when needed.", revoked));
    }

    public record RevokeResult(String message, List<String> revokedTokens) { }

    @DeleteMapping("/files")
    public ResponseEntity<DeleteResult> deleteFiles(
            @RequestBody DeleteRequest request
    ) throws IOException {

        if (request.tokens() == null || request.tokens().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new DeleteResult(
                            "Select at least one file.",
                            formatStorage(shareLinkService.usedStorage()),
                            List.of()
                    ));
        }

        List<String> deletedTokens = shareLinkService
                .deleteByTokens(request.tokens());
        int deletedCount = deletedTokens.size();
        String noun = deletedCount == 1 ? "file" : "files";

        return ResponseEntity.ok(new DeleteResult(
                deletedCount + " " + noun + " deleted.",
                formatStorage(shareLinkService.usedStorage()),
                deletedTokens
        ));
    }

    private ResponseEntity<Resource> createDownloadResponse(
            FileReference file
    ) throws IOException {

        String safeFilename;

        try {
            safeFilename = Path.of(file.filename())
                    .getFileName()
                    .toString();
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest().build();
        }

        Path filePath = file.path().normalize();

        if (!filePath.startsWith(shareLinkService.storageDirectory())
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
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
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

    private void writeZip(
            OutputStream outputStream,
            List<FileReference> files
    ) throws IOException {
        writeZip(outputStream, files, false);
    }

    private void writeZip(
            OutputStream outputStream,
            List<FileReference> files,
            boolean requireAllFiles
    ) throws IOException {
        try (ZipOutputStream zipOutputStream =
                     new ZipOutputStream(outputStream)) {
            for (FileReference file : files) {
                Path filePath = file.path().normalize();

                if (!filePath.startsWith(shareLinkService.storageDirectory())
                        || !Files.isRegularFile(filePath)) {
                    if (requireAllFiles) {
                        throw new IOException("A selected file became unavailable.");
                    }
                    continue;
                }

                zipOutputStream.putNextEntry(new ZipEntry(file.filename()));
                Files.copy(filePath, zipOutputStream);
                zipOutputStream.closeEntry();
            }
        }
    }

    private FilesSnapshot createFilesSnapshot() {
        ShareLinkService.StorageSnapshot snapshot = shareLinkService.snapshot();
        List<FileView> files = snapshot.files().stream()
                .map(file -> new FileView(file.filename(), file.token(), formatExpiration(file.expiresAt())))
                .toList();
        return new FilesSnapshot(files, formatStorage(snapshot.usedBytes()));
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

    private String formatExpiration(Instant expiresAt) {
        if (expiresAt == null) {
            return "Never expires";
        }

        return "Expires " + EXPIRATION_FORMATTER.format(expiresAt);
    }

    private String formatStorage(long bytes) {
        double megabytes = bytes / (1024.0 * 1024.0);

        return String.format("%.1f MB of 5 GB used", megabytes);
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

}
