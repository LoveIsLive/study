package com.kwang.study.common;

import com.kwang.study.enums.NodeTypeEnum;

import java.util.List;

public class FileStorageConstant {
    public static final List<Integer> COMMON_FILE_TYPE = List.of(NodeTypeEnum.FILE.getCode(),
            NodeTypeEnum.DIR.getCode());
}
