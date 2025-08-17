package com.kwang.study.fs.pojo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对应 file_chunk 表的实体类
 */
@Data
public class FileChunk implements Serializable {
    private static final long serialVersionUID = 5893046587524819476L;
    /**
     * 关联的node.id
     */
    private Long fileId;

    /**
     * 分片索引（从0开始）
     */
    private Integer chunkIndex;

    /**
     * 分片存储的唯一key, 形式file_id/chunk_index
     */
    private String key;

    /**
     * 状态,0表示初态-分片刚上传,1表示合并中，2表示合并成功，3表示合并失败
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 分片大小
     */
    private Integer size;
}