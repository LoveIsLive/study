package com.kwang.study.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HashRefNum {
    private String hash;
    private String refPath;
    private Integer refNum;
}
