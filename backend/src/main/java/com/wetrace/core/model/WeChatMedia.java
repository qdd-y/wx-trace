package com.wetrace.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeChatMedia {
    private String key;
    private String name;
    private String path;
    private long size;
    private long modifyTime;
    private String type;
    private String dir1;
    private String dir2;
    private byte[] data;
    private byte[] extraBuffer;
}
