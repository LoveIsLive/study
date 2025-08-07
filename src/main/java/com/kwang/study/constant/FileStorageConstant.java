package com.kwang.study.constant;

import com.kwang.study.enums.NodeTypeEnum;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class FileStorageConstant {
    public static final List<Integer> COMMON_FILE_TYPE = List.of(NodeTypeEnum.FILE.getCode(),
            NodeTypeEnum.DIR.getCode());



    public static final List<Integer> ALL_FILE_TYPE = Arrays.stream(NodeTypeEnum.values())
            .map(NodeTypeEnum::getCode).collect(Collectors.toList());
}
