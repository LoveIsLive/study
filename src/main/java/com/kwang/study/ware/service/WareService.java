package com.kwang.study.ware.service;

import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.dto.result.*;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.fs.util.TextMimeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_PARTIAL_CONTENT;

@Service
public class WareService {
    @Autowired
    private FileStorageService fsService;

    @Transactional
    public VoidResult createDirectory(String path) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.createDirectory(actualPath);
    }

    /**
     * 创建文件（处理文件去重）
     * 注意：仅仅小文件可以调用这个方法，不会出现OOM
     */
    @Transactional
    public VoidResult createFile(String path, InputStream fileStream, String mimeTypeName) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.createFile(actualPath, fileStream, mimeTypeName);
    }

    /**
     * 递归删除目录节点（危险操作！！！）
     */
    @Transactional
    public VoidResult deleteDirNode(String path) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.deleteDirObject(actualPath);
    }


    /**
     * 删除文件节点（带引用计数处理）
     */
    @Transactional
    public VoidResult deleteFileNode(String path) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.deleteFileObject(actualPath);
    }

    /**
     * 重命名文件节点
     */
    @Transactional
    public VoidResult renameFileNode(String path, String newName) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.updateFileObject(actualPath, newName, null, null);
    }

    /**
     * 重命名文件节点
     */
    @Transactional
    public VoidResult renameDirNode(String path, String newName) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.updateDirObject(actualPath, newName);
    }

    /**
     * 列出指定目录的详细内容
     */
    @Transactional
    public DirObjectResult listDirectoryDetailContents(String path) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.getDirectoryObject(actualPath);
    }

    /**
     * 获取节点的详细信息（包含MIME类型）
     */
    @Transactional
    public GenericObjectResult getNodeDetails(String path) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.getObjectDesc(actualPath);
    }

    public void downloadFile(String path, String mode, HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        String actualPath = buildActualPath(path);
        FileObjectResult fileObject = fsService.getFileObject(actualPath);
        try (InputStream is = fileObject.getContent()) {
            if (is == null) {
                response.setStatus(SC_NOT_FOUND);
                response.getWriter().write("File data not found in storage");
                return;
            }

            long fileSize = fileObject.getSize();
            // 处理Range请求
            long[] range = parseRangeHeader(request, fileSize);
            long start = range[0];
            long end = range[1];
            long length = end - start + 1;

            String mimeTypeName = fileObject.getMimeTypeName();
            String contentType = mimeTypeName != null ? mimeTypeName : "application/octet-stream";

            // 如果是文本类型的文件，明确指定UTF-8编码
            if (TextMimeUtil.isTextBased(mimeTypeName)) {
                contentType += "; charset=UTF-8";
            }
            response.setContentType(contentType);
            String encodedFileName = URLEncoder.encode(fileObject.getName(), StandardCharsets.UTF_8).replace("+", "%20");
            String dispositionType = "inline".equals(mode) ? "inline" : "attachment";
            response.setHeader("Content-Disposition", dispositionType + "; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName);
            response.setHeader("Accept-Ranges", "bytes");

            // 根据是否是范围请求设置不同的响应头
            if (request.getHeader("Range") != null) {
                response.setStatus(SC_PARTIAL_CONTENT);
                response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
                response.setContentLengthLong(length);
            } else {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentLengthLong(fileSize);
            }

            // 跳过起始字节
            if (start > 0) {
                long bytesToSkip = start;
                while (bytesToSkip > 0) {
                    long skipped = is.skip(bytesToSkip);
                    if (skipped <= 0) {
                        // 如果无法再跳过任何字节，但还没到目标位置，说明流出了问题
                        throw new IOException("Unable to skip to the specified start position.");
                    }
                    bytesToSkip -= skipped;
                }
            }

            // 流式传输
            try (OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long bytesToWrite = length;
                while (bytesToWrite > 0 && (bytesRead = is.read(buffer, 0, (int) Math.min(buffer.length, bytesToWrite))) != -1) {
                    os.write(buffer, 0, bytesRead);
                    bytesToWrite -= bytesRead;
                }
                os.flush();
            }
        }
    }

    public VoidResult searchNodesBFS(String path, String namePattern, Consumer<SearchNodeResult> resultConsumer) {
        String actualPath = buildActualPath(path);
        return fsService.searchNodesBFS(actualPath, namePattern, resultConsumer);
    }

    /**
     * 初始化分块上传，之后的分块上传需要带上result的uploadId.
     */
    @Transactional
    public InitMultiUploadResult initMultiUpload(String path, String mimeTypeName) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.initMultiUpload(actualPath, mimeTypeName);
    }

    /**
     * 上传分块，该方法在所有分块上传完毕后，会自动合并。
     */
    @Transactional
    public UploadChunkResult uploadChunkAndAutoMerge(String uploadId, Integer chunkIndex, Integer totalChunks,
                                              InputStream chunkStream) throws IOException {
        return fsService.uploadChunkAndAutoMerge(uploadId, chunkIndex, totalChunks, chunkStream);
    }

    @Transactional
    public MimeTypeResult getAllMimeTypeNames() {
        return fsService.getAllMimeTypeNames();
    }

    /**
     * 解析Range头，返回[start, end]
     */
    private long[] parseRangeHeader(HttpServletRequest request, long fileSize) {
        String rangeHeader = request.getHeader("Range");
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            return new long[]{0, fileSize - 1};
        }
        // "bytes=0-499" or "bytes=500-" or "bytes=-500"
        String rangeValue = rangeHeader.substring(6);
        long start = 0, end = fileSize - 1;

        if (rangeValue.startsWith("-")) { // e.g., "-500" (last 500 bytes)
            long lastBytes = Long.parseLong(rangeValue.substring(1));
            start = Math.max(0, fileSize - lastBytes);
        } else {
            String[] parts = rangeValue.split("-");
            start = Long.parseLong(parts[0]);
            if (parts.length > 1 && !parts[1].isEmpty()) {
                end = Long.parseLong(parts[1]);
            }
        }

        // 保证范围有效
        if (start < 0 || start >= fileSize || start > end) {
            return new long[]{0, fileSize - 1};
        }
        return new long[]{start, Math.min(end, fileSize - 1)};
    }


    // 返回用户该模块的根目录，不以/结尾
    private String buildBasePath() {
        return FileStorageModuleNameEnum.WARE_NAME.getModuleName();
    }

    private String buildActualPath(String path) {
        String basePath = buildBasePath();
        if (!path.startsWith("/"))
            path = "/" + path;
        if (path.endsWith("/"))
            path = path.substring(0, path.length() - 1);
        return basePath + path;
    }
}
