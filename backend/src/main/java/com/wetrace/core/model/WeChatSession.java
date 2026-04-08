package com.wetrace.core.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeChatSession {
    private String userName;
    private long order;
    private String nickName;
    private String content;
    private long nTime;

    private String username;
    private String summary;
    private long lastTimestamp;
    private String lastMsgSender;
    private String lastSenderDisplayName;

    private String name;
    private String remark;
    private String smallHeadURL;
    private String bigHeadURL;
    private int messageCount;
    private String lastMessage;
    private long lastTime;
    private int memberCount;
    private boolean pinned;
    private int type;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime lastTimeFormatted;

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public LocalDateTime getLastTimeFormatted() {
        if (lastTimeFormatted != null) {
            return lastTimeFormatted;
        }
        if (lastTime > 0) {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(lastTime), ZoneId.systemDefault());
        }
        if (lastTimestamp > 0) {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(lastTimestamp), ZoneId.systemDefault());
        }
        return null;
    }
}
