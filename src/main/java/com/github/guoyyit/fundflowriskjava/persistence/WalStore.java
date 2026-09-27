package com.github.guoyyit.fundflowriskjava.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Append-only write-ahead log. Each committed unit (a batch of transfers or a
 * single delete) is serialized as one JSON line and fsynced before the writer
 * mutates the in-memory graph. On restart only complete lines are replayed:
 * a torn tail line (crash between WAL write and graph mutation, or mid-write)
 * is truncated on open, so uncommitted updates never produce partial state.
 */
public class WalStore {

    private static final String TYPE_BATCH = "batch";
    private static final String TYPE_DELETE = "delete";

    /** One WAL line. Exactly one of (records, txId) is meaningful per type. */
    public record WalEntry(String type, List<TransferRecord> records, String txId) {
    }

    private final Path walFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private FileChannel channel;

    public WalStore(Path walFile) {
        this.walFile = walFile;
        try {
            if (walFile.getParent() != null) {
                Files.createDirectories(walFile.getParent());
            }
            truncateCorruptTail();
            this.channel = FileChannel.open(walFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open WAL " + walFile, e);
        }
    }

    /** Appends a committed batch of transfers and fsyncs. */
    public synchronized void appendBatch(List<TransferRecord> records) {
        append(new WalEntry(TYPE_BATCH, List.copyOf(records), null));
    }

    /** Appends a committed delete (rollback) of one transfer and fsyncs. */
    public synchronized void appendDelete(String txId) {
        append(new WalEntry(TYPE_DELETE, null, txId));
    }

    private void append(WalEntry entry) {
        try {
            byte[] json = mapper.writeValueAsBytes(entry);
            byte[] line = new byte[json.length + 1];
            System.arraycopy(json, 0, line, 0, json.length);
            line[json.length] = (byte) '\n';
            channel.write(java.nio.ByteBuffer.wrap(line));
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append to WAL " + walFile, e);
        }
    }

    /** Replays all committed entries in write order. */
    public void replay(Consumer<List<TransferRecord>> onBatch, Consumer<String> onDelete) {
        if (!Files.exists(walFile)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(walFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                WalEntry entry = mapper.readValue(line, WalEntry.class);
                if (TYPE_BATCH.equals(entry.type()) && entry.records() != null) {
                    onBatch.accept(entry.records());
                } else if (TYPE_DELETE.equals(entry.type()) && entry.txId() != null) {
                    onDelete.accept(entry.txId());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to replay WAL " + walFile, e);
        }
    }

    /**
     * Removes a trailing torn line left by a crash: scans the file and keeps
     * only the prefix made of complete, parseable lines.
     */
    private void truncateCorruptTail() throws IOException {
        if (!Files.exists(walFile)) {
            return;
        }
        List<String> lines = Files.readAllLines(walFile, StandardCharsets.UTF_8);
        long validBytes = 0;
        List<String> validLines = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || isParseable(line)) {
                validLines.add(line);
                validBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
            } else {
                break;
            }
        }
        long fileSize = Files.size(walFile);
        if (validBytes < fileSize) {
            try (RandomAccessFile raf = new RandomAccessFile(walFile.toFile(), "rw")) {
                raf.setLength(validBytes);
            }
        }
    }

    private boolean isParseable(String line) {
        try {
            mapper.readValue(line, WalEntry.class);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
