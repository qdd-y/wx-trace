package com.wetrace.core.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeChatMessage {
    @JsonIgnore
    private String version;

    @JsonIgnore
    private long seq;

    @JsonIgnore
    private long timestamp;

    private String talker;
    private String talkerName;
    private boolean isChatRoom;
    private String sender;
    private String senderName;
    private boolean isSelf;
    private int type;
    private int subType;
    private String content;
    private Map<String, Object> contents;
    private String bigHeadURL;
    private String smallHeadURL;

    private long sortSeq;
    private long serverId;
    private int status;
    private String thumb;
    private String xmlContent;
    private byte[] compressContent;
    private byte[] packedInfoData;
    private String realSender;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime time;

    public LocalDateTime getTime() {
        if (time != null) {
            return time;
        }
        if (timestamp > 0) {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault());
        }
        return null;
    }

    public String getTypeDesc() {
        return switch (type) {
            case 1 -> "文本";
            case 3 -> "图片";
            case 34 -> "语音";
            case 42 -> "名片";
            case 43 -> "视频";
            case 47 -> "表情";
            case 48 -> "位置";
            case 49 -> "分享";
            case 50 -> "语音通话";
            case 10000 -> "系统";
            default -> "其他(" + type + ")";
        };
    }
}
