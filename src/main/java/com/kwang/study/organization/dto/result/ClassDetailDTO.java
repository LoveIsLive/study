package com.kwang.study.organization.dto.result;

import com.kwang.study.organization.pojo.Classes;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ClassDetailDTO extends Classes {
    private Integer memberCount;
}