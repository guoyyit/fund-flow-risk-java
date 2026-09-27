package com.github.guoyyit.fundflowriskjava.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 预写日志：每个已提交事务追加为一行 JSON（含全部新增与删除），
 * 落盘并 fsync 后才算提交成功。崩溃时最后一行可能不完整，
 * 重放时无法解析的行被忽略，未提交事务不产生部分命中。
 */
public class WalStore implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一个事务：一批新增记录 + 一批删除的记录 id。 */
    public record TxEntry(List<TransferRecord> adds, List<String> deletes) {
    }

    public interface ReplayHandler {
        void onRecord(TransferRecord record);

        void onDelete(String recordId);
    }

    private final Path file;
    private final BufferedWriter writer;

    public WalStore(Path file) {
        this.file = file;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("无法打开 WAL 文件: " + file, e);
        }
    }

    /** 追加一个已提交事务：整行写入并 flush 后返回。 */
    public synchronized void appendCommitted(List<TransferRecord> adds, List<String> deletes) {
        try {
            writer.write(MAPPER.writeValueAsString(new TxEntry(adds, deletes)));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("WAL 写入失败: " + file, e);
        }
    }

    /** 重放所有已提交事务；遇到无法解析的尾部行即停止。 */
    public void replay(ReplayHandler handler) {
        if (!Files.exists(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                TxEntry entry;
                try {
                    entry = MAPPER.readValue(line, TxEntry.class);
                } catch (IOException corrupt) {
                    // 崩溃残留的未提交事务，忽略并停止重放
                    break;
                }
                if (entry.adds() != null) {
                    entry.adds().forEach(handler::onRecord);
                }
                if (entry.deletes() != null) {
                    entry.deletes().forEach(handler::onDelete);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("WAL 重放失败: " + file, e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException("WAL 关闭失败: " + file, e);
        }
    }
}
