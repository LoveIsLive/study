package com.kwang.study.homework.pojo;

import lombok.*;

/**
 * @author kwang
 * @date 2025/08/28
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AttachmentDetail extends Attachment {
    private static final long serialVersionUID = 562316552837964769L;
    /**
     * mime类型名称
     */
    private String mimeTypeName;
}
