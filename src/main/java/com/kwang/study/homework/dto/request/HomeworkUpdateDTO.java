package com.kwang.study.homework.dto.request;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true) // 继承自 HomeworkCreateDTO 以复用字段
public class HomeworkUpdateDTO extends HomeworkCreateDTO {

    /**
     * 需要删除的已有附件的 ID 列表
     */
    private List<Long> attachmentIdsToDelete;
}
