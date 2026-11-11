package com.example.quickdrop.controller;

import com.example.quickdrop.share.ShareLinkService;
import com.example.quickdrop.share.ShareLinkService.FileReference;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.example.quickdrop.controller.TransferResponses.*;

@Controller
public class DownloadController {
    private final ShareLinkService shareLinkService;

    public DownloadController(ShareLinkService shareLinkService) {
        this.shareLinkService = shareLinkService;
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

}
