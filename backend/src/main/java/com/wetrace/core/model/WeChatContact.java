package com.wetrace.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeChatContact {
    private String username;
    private int localType;
    private String alias;
    private String remark;
    private String nickName;
    private String smallHeadURL;
    private String bigHeadURL;

    private String userName;
    private String reserved1;

    private String name;
    private String country;
    private String province;
    private String city;
    private long addTime;
    private String source;

    public String getName() {
        if (name != null && !name.isEmpty()) {
            return name;
        }
        if (remark != null && !remark.isEmpty()) {
            return remark;
        }
        if (nickName != null && !nickName.isEmpty()) {
            return nickName;
        }
        return userName != null ? userName : username;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getLocalType() {
        return localType;
    }

    public void setLocalType(int localType) {
        this.localType = localType;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getSmallHeadURL() {
        return smallHeadURL;
    }

    public void setSmallHeadURL(String smallHeadURL) {
        this.smallHeadURL = smallHeadURL;
    }

    public String getBigHeadURL() {
        return bigHeadURL;
    }

    public void setBigHeadURL(String bigHeadURL) {
        this.bigHeadURL = bigHeadURL;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getReserved1() {
        return reserved1;
    }

    public void setReserved1(String reserved1) {
        this.reserved1 = reserved1;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public long getAddTime() {
        return addTime;
    }

    public void setAddTime(long addTime) {
        this.addTime = addTime;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
