package com.nightshift.service.scan;

import com.nightshift.model.entity.LogSource;
import com.nightshift.model.entity.ScannedFile;
import com.nightshift.model.entity.ScanRun;
import com.nightshift.repository.ScannedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Reads only the new bytes that have been appended to each log file since the last scan.
 *
 * <p>Algorithm (per file):
 * <ol>
 *   <li>Compute a SHA-256 hash of the first {@value #HEADER_BYTES} bytes of the file.
 *   <li>Look up the {@link ScannedFile} record for this path.
 *   <li>If the stored {@code content_hash} differs, the file was rotated: reset offset to 0.
 *   <li>Skip to {@code byte_offset} and read to end-of-file.
 *   <li>Parse the bytes into {@link LogEvent} objects via {@link LogEventParser}.
 *   <li>Update the {@link ScannedFile} record with the new offset and hash inside the
 *       caller's transaction.
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncrementalLogReader {

    /** Number of bytes hashed to detect file rotation. */
    static final int HEADER_BYTES = 4096;

    private final ScannedFileRepository scannedFileRepository;
    private final LogEventParser parser;

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Scans all files under the log source's root path that match the configured glob,
     * returning only the events found after each file's stored byte offset.
     *
     * <p>Each matching file is read atomically (find → read → update checkpoint) inside
     * the outer transaction provided by the caller.
     *
     * @param logSource the log source to scan
     * @param scanRun   the current scan run (set on updated {@link ScannedFile} records)
     * @return list of {@link FileReadResult}, one per file that had any data
     */
    @Transactional
    public List<FileReadResult> readNewEvents(LogSource logSource, ScanRun scanRun) {
        List<FileReadResult> results = new ArrayList<>();
        Path root = Path.of(logSource.getRootPath());

        if (!Files.isDirectory(root)) {
            log.warn("Log source root does not exist or is not a directory: {}", root);
            return results;
        }

        PathMatcher matcher = FileSystems.getDefault()
                .getPathMatcher("glob:" + logSource.getFileGlob());

        try {
            List<Path> files = collectMatchingFiles(root, matcher);
            for (Path file : files) {
                try {
                    FileReadResult result = processFile(file, root, logSource, scanRun);
                    if (result != null) {
                        results.add(result);
                    }
                } catch (IOException e) {
                    log.error("Failed to read log file {}: {}", file, e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to walk log source root {}: {}", root, e.getMessage(), e);
        }

        return results;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private List<Path> collectMatchingFiles(Path root, PathMatcher matcher) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path rel = root.relativize(file);
                if (matcher.matches(rel) || matcher.matches(file.getFileName())) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());
        return files;
    }

    /**
     * Processes one file: compute hash, load checkpoint, seek, parse, update checkpoint.
     * Returns null if nothing new was read (offset at end-of-file).
     */
    private FileReadResult processFile(
            Path file, Path root, LogSource logSource, ScanRun scanRun) throws IOException {

        long fileSize = Files.size(file);
        String relativePath = root.relativize(file).toString().replace('\\', '/');
        Instant lastModified = Files.getLastModifiedTime(file).toInstant();

        // Compute header hash (first HEADER_BYTES)
        String headerHash = computeHeaderHash(file);

        // Load or initialise checkpoint
        Optional<ScannedFile> existingOpt = scannedFileRepository
                .findByLogSourceIdAndRelativePath(logSource.getId(), relativePath);

        long readFrom;
        ScannedFile checkpoint;

        if (existingOpt.isPresent()) {
            checkpoint = existingOpt.get();
            if (!headerHash.equals(checkpoint.getContentHash())) {
                // file was rotated — reset offset
                log.info("File rotation detected for {}, resetting offset", relativePath);
                checkpoint.setByteOffset(0L);
                checkpoint.setContentHash(headerHash);
            }
            readFrom = checkpoint.getByteOffset();
        } else {
            // first time we see this file
            checkpoint = ScannedFile.builder()
                    .logSource(logSource)
                    .relativePath(relativePath)
                    .sizeBytes(fileSize)
                    .byteOffset(0L)
                    .contentHash(headerHash)
                    .lastModifiedAt(lastModified)
                    .lastScannedAt(Instant.now())
                    .lastScanRun(scanRun)
                    .build();
            readFrom = 0L;
        }

        if (readFrom >= fileSize) {
            // nothing new — update metadata and skip
            updateCheckpoint(checkpoint, fileSize, headerHash, lastModified, scanRun);
            return null;
        }

        // Read from offset to end
        List<LogEvent> events = readEvents(file, readFrom);

        // Update checkpoint
        updateCheckpoint(checkpoint, fileSize, headerHash, lastModified, scanRun);

        return new FileReadResult(relativePath, file.toString(), readFrom, fileSize, events);
    }

    private List<LogEvent> readEvents(Path file, long fromOffset) throws IOException {
        // Determine start line number for accurate line tracking
        // (approximate: count newlines in bytes 0..fromOffset)
        int startLine = 1;
        if (fromOffset > 0) {
            startLine = countLines(file, fromOffset) + 1;
        }

        try (InputStream fullStream = Files.newInputStream(file)) {
            long skipped = 0;
            while (skipped < fromOffset) {
                long n = fullStream.skip(fromOffset - skipped);
                if (n <= 0) break;
                skipped += n;
            }
            return parser.parse(fullStream, startLine);
        }
    }

    private int countLines(Path file, long upToOffset) throws IOException {
        int count = 0;
        try (InputStream in = Files.newInputStream(file)) {
            long remaining = upToOffset;
            byte[] buf = new byte[8192];
            while (remaining > 0) {
                int read = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (read < 0) break;
                for (int i = 0; i < read; i++) {
                    if (buf[i] == '\n') count++;
                }
                remaining -= read;
            }
        }
        return count;
    }

    private void updateCheckpoint(
            ScannedFile checkpoint, long fileSize, String headerHash,
            Instant lastModified, ScanRun scanRun) {

        checkpoint.setSizeBytes(fileSize);
        checkpoint.setByteOffset(fileSize);   // advance to end-of-file
        checkpoint.setContentHash(headerHash);
        checkpoint.setLastModifiedAt(lastModified);
        checkpoint.setLastScannedAt(Instant.now());
        checkpoint.setLastScanRun(scanRun);
        scannedFileRepository.save(checkpoint);
    }

    private String computeHeaderHash(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = sha256();
            byte[] buf = new byte[HEADER_BYTES];
            int total = 0;
            int read;
            while (total < HEADER_BYTES && (read = in.read(buf, total, HEADER_BYTES - total)) > 0) {
                total += read;
            }
            md.update(buf, 0, total);
            return HexFormat.of().formatHex(md.digest());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── inner record ─────────────────────────────────────────────────────────

    /**
     * Carries the events read from one file in a single pass.
     */
    public record FileReadResult(
            String relativePath,
            String absolutePath,
            long fromOffset,
            long toOffset,
            List<LogEvent> events
    ) {}
}
