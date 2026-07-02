package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 分页结果包装。
 */
@Data
@Builder
public class PageResultVO<T> {
    private List<T> records;
    private long total;
}
