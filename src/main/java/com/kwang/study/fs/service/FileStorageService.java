package com.kwang.study.fs.service;


import com.kwang.study.fs.dto.result.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * 向外部提供的文件系统接口
 */
public interface FileStorageService {

    /**
     * 根据完整的unix path创建目录，父目录应该存在
     * @param path 完整的unix path
     * @return 目录结果对象
     */
    VoidResult createDirectory(String path) throws IOException;

    /**
     * 根据完整的unix path创建文件，父目录应该存在
     * @param path 完整的unix path
     * @param fileStream 文件输入流
     * @param mimeTypeName 文件mimeType
     * @return 文件结果对象
     */
    VoidResult createFile(String path, InputStream fileStream, String mimeTypeName) throws IOException;

    /**
     * 根据完整的unix path 删除文件。如果path最后是一段目录，应当抛出异常。
     * @param path 完整的unix path
     * @return void
     */
    VoidResult deleteFileObject(String path) throws IOException;

    /**
     * 根据完整的unix path 删除目录。如果path最后是一段文件，应当抛出异常。
     * @param path 完整的unix path
     * @return void
     */
    VoidResult deleteDirObject(String path) throws IOException;
    /**
     * 根据完整的unix path 更新文件。如果path最后是一段目录，应当抛出异常。
     * @param path 完整的unix path
     * @param newName 最后一段的新名称
     * @return void
     */
    VoidResult updateFileObject(String path, String newName, InputStream fileStream,
                                String mimeTypeName) throws IOException;

    /**
     * 根据完整的unix path 更新目录。如果path最后是一段文件，应当抛出异常。
     * @param path 完整的unix path
     * @param newName 最后一段的新名称
     * @return void
     */
    VoidResult updateDirObject(String path, String newName) throws IOException;

    /**
     * 根据完整的unix path得到目录，如果目录不存在应当抛出异常
     * @param path 完整的unix path
     * @return 目录结果对象
     */
    DirObjectResult getDirectoryObject(String path) throws IOException;

    /**
     * 根据完整的unix path得到文件，如果其中目录不存在、或最后文件不存在应当抛出异常
     * @param path 完整的unix path
     * @return 文件结果对象
     */
    FileObjectResult getFileObject(String path) throws IOException;

    /**
     * 根据完整的unix path得到节点描述
     * @param path 完整的unix path
     * @return
     */
    GenericObjectResult getObjectDesc(String path) throws IOException;

    /*
    分片上传文件方法
     */

    /**
     * 初始化分块上传，之后的分块上传需要带上result的uploadId.
     * @param path 完整的unix path
     * @param mimeTypeName 文件mimeType名称
     * @return result
     * @throws IOException 可能的IO异常
     */
    InitMultiUploadResult initMultiUpload(String path, String mimeTypeName) throws IOException;

    /**
     * 上传分块，该方法在所有分块上传完毕后，会自动合并。
     * @param uploadId initMultiUpload方法返回的uploadId，分块上传的凭证
     * @param chunkIndex 上传的第几个块
     * @param totalChunks 总共的块
     * @param chunkStream 分块输入流
     * @return 上传分块结果
     * @throws IOException 可能的IO异常
     */
    UploadChunkResult uploadChunkAndAutoMerge(String uploadId, Integer chunkIndex,
                            Integer totalChunks, InputStream chunkStream) throws IOException;


    /**
     * 在指定目录下递归模糊搜索节点，并将结果（包含完整路径）通过回调函数流式返回。
     * 使用广度优先搜索（BFS）以减少数据库压力，并在遍历时构建路径。
     * @param path 完整的unix path
     * @param namePattern 模糊的文件名
     * @param resultConsumer 消费者
     * @return void
     */
    VoidResult searchNodesBFS(String path, String namePattern, Consumer<SearchNodeResult> resultConsumer);

    MimeTypeResult getAllMimeTypeNames();
}
