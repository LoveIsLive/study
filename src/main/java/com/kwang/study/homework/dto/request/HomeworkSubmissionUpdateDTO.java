package com.kwang.study.homework.dto.request;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class HomeworkSubmissionUpdateDTO extends SubmissionCreateDTO {

    /**
     * 需要删除的已有附件的 ID 列表
     */
    private List<Long> attachmentIdsToDelete;
}
