package com.kwang.study.fs.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface FileStorage {

    /*
    存储一个文件，要求调用方保证key的唯一性，如果key相同则更改文件内容
     */
    void putFile(String key, InputStream file) throws IOException;

    /*
    通过key获取文件，如果不存在，则返回null
     */
    InputStream getFile(String key) throws IOException;

    /*
    通过key删除文件，如果不存在，no-op。
     */
    void deleteFile(String key) throws IOException;

    /*
    返回存储中是否包含这个key
     */
    boolean contains(String key);

    /*
    打开一个文件，若文件不存在则创建
     */
    OutputStream openFile(String key) throws IOException;
}
