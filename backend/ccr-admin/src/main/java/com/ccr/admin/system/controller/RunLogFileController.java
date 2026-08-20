package com.ccr.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 运行日志文件浏览(运行日志监控):列出后台 logs/ 目录日志文件、下载、尾部预览。
 * 权限:仅 admin。路径安全:只接受纯文件名(无路径分隔/.. /绝对路径),且仅 .log/.out,并校验落在 logs/ 目录内。
 */
@RestController
@RequestMapping("/system/run-log/files")
@SaCheckRole("admin")
@Slf4j
public class RunLogFileController {

    @Value("${ccr.run-log.logs-path:logs}")
    private String logsPath;

    private Path baseDir() {
        return Path.of(logsPath).toAbsolutePath().normalize();
    }

    /** 日志文件列表(按修改时间倒序:名称/大小/修改时间) */
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        Path base = baseDir();
        List<Map<String, Object>> files = new ArrayList<>();
        if (!Files.isDirectory(base)) {
            return R.ok(files);
        }
        try (Stream<Path> stream = Files.list(base)) {
            stream.filter(p -> Files.isRegularFile(p) && isLogName(p.getFileName().toString()))
                    .sorted(Comparator.comparingLong(this::lastModifiedOf).reversed())
                    .forEach(p -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", p.getFileName().toString());
                        m.put("size", sizeOf(p));
                        m.put("lastModified", lastModifiedOf(p));
                        files.add(m);
                    });
        } catch (IOException e) {
            log.warn("[run-log] 日志目录读取失败: {}", e.getMessage());
        }
        return R.ok(files);
    }

    /** 下载单个日志文件(流式返回,避免大文件 OOM) */
    @GetMapping("/download")
    public ResponseEntity<InputStreamResource> download(@RequestParam String name) {
        Path file = resolveSafe(name);
        try {
            InputStreamResource res = new InputStreamResource(Files.newInputStream(file));
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.parseMediaType("text/plain; charset=utf-8"))
                    .body(res);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "日志文件不存在或读取失败:" + name);
        }
    }

    /** 日志文件尾部预览(默认末 200 行,上限 2000 行;大文件只扫尾部 1MB) */
    @GetMapping("/tail")
    public R<Map<String, Object>> tail(@RequestParam String name,
                                       @RequestParam(defaultValue = "200") int lines) {
        Path file = resolveSafe(name);
        try {
            int n = Math.min(Math.max(lines, 1), 2000);
            List<String> tail = readTail(file, n);
            Map<String, Object> data = new HashMap<>();
            data.put("name", name);
            data.put("lines", tail);
            return R.ok(data);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "日志文件不存在或读取失败:" + name);
        }
    }

    /** 路径安全:纯文件名(不允许路径/..),且仅 .log/.out,并校验解析后仍在日志目录内 */
    private Path resolveSafe(String name) {
        if (name == null || name.isBlank() || !isLogName(name)
                || name.contains("/") || name.contains("\\") || name.contains("..") || name.contains(":")) {
            throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "非法日志文件名");
        }
        Path base = baseDir();
        Path file = base.resolve(name).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "日志文件不存在:" + name);
        }
        return file;
    }

    private static boolean isLogName(String name) {
        return name.endsWith(".log") || name.endsWith(".out");
    }

    private long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private long lastModifiedOf(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 从文件尾部读 n 行(最多扫尾部 1MB,避免大文件全读) */
    private List<String> readTail(Path file, int n) throws IOException {
        long fileSize = Files.size(file);
        long scan = Math.min(fileSize, 1_000_000L);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long start = Math.max(fileSize - scan, 0);
            raf.seek(start);
            byte[] buf = new byte[(int) (fileSize - start)];
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            String[] all = text.split("\n", -1);
            int from = Math.max(0, all.length - n);
            List<String> out = new ArrayList<>(all.length - from);
            for (int i = from; i < all.length; i++) {
                out.add(all[i]);
            }
            return out;
        }
    }
}
