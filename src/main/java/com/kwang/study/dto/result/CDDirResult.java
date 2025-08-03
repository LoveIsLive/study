package com.kwang.study.dto.result;

import com.kwang.study.dto.NodeDetailDTO;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class CDDirResult implements Serializable {
    private static final long serialVersionUID = -5343705748922159422L;

    private Long dirId; // 目录id
    private String dirPath; // 目录完整路径
    private List<NodeDetailDTO> nodeDetailDTOS; // 目录下的所有子节点详细信息
}
