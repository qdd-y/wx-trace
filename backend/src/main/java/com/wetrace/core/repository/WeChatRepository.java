package com.wetrace.core.repository;

import com.wetrace.core.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;
import java.util.zip.InflaterInputStream;

import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4Factory;

/**
 * 微信数据仓库
 *
 * 完整对齐 Go 版本 store/repo/*.go 的所有 SQL 查询逻辑
 * 支持 v3 / v4 / darwinv3 三种数据库 schema
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class WeChatRepository {

    private final SqlitePool pool;
    private final DbRouter router;

    // 缓存
    private final Map<String, ContactProfile> contactProfileCache = new ConcurrentHashMap<>();
    private final Set<String> missingContactProfileCache = ConcurrentHashMap.newKeySet();
    private volatile String currentUserWxid;

    // ==================== 会话 ====================

    public List<WeChatSession> getSessions(String keyword, int limit, int offset) throws SQLException {
        String dbPath = router.getSessionDBPath();
        Connection conn = pool.getConnection(dbPath);
        if (pool.isTableExist(dbPath, "SessionTable")) {
            return queryV4Sessions(conn, keyword, limit, offset);
        }
        if (pool.isTableExist(dbPath, "Session")) {
            return queryV3Sessions(conn, keyword, limit, offset);
        }
        throw new SQLException("未在 session 数据库中找到 SessionTable 或 Session 表: " + dbPath);
    }

    private List<WeChatSession> queryV4Sessions(Connection conn, String keyword, int limit, int offset)
            throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT username, summary, last_timestamp, last_msg_sender, last_sender_display_name, sort_timestamp ");
        sql.append("FROM SessionTable WHERE username != '@placeholder_foldgroup'");

        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (username = ? OR last_sender_display_name = ?)");
            args.add(keyword);
            args.add(keyword);
        }

        sql.append(" ORDER BY sort_timestamp DESC");
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
            if (offset > 0) sql.append(" OFFSET ").append(offset);
        }

        List<WeChatSession> sessions = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                WeChatSession s = WeChatSession.builder()
                    .username(rs.getString(1))
                    .content(rs.getString(2))
                    .lastTime(rs.getLong(3))
                    .lastMsgSender(rs.getString(4))
                    .lastSenderDisplayName(rs.getString(5))
                    .order(rs.getLong(6))
                    .build();
                enrichSession(conn, s);
                sessions.add(s);
            }
        }
        return sessions;
    }

    private List<WeChatSession> queryV3Sessions(Connection conn, String keyword, int limit, int offset)
            throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT strUsrName, nOrder, strNickName, strContent, nTime ");
        sql.append("FROM Session WHERE strUsrName != '@placeholder_foldgroup'");

        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (strUsrName = ? OR strNickName = ?)");
            args.add(keyword);
            args.add(keyword);
        }

        sql.append(" ORDER BY nOrder DESC");
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
            if (offset > 0) sql.append(" OFFSET ").append(offset);
        }

        List<WeChatSession> sessions = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                WeChatSession s = WeChatSession.builder()
                    .userName(rs.getString(1))
                    .order(rs.getLong(2))
                    .nickName(rs.getString(3))
                    .content(rs.getString(4))
                    .nTime(rs.getLong(5))
                    .build();
                enrichSession(conn, s);
                sessions.add(s);
            }
        }
        return sessions;
    }

    /** 填充会话的头像和昵称 */
    private void enrichSession(Connection conn, WeChatSession s) {
        String wxid = s.getUserName() != null ? s.getUserName() : s.getUsername();
        if (wxid == null || wxid.isEmpty()) return;

        try {
            var profile = getContactProfile(wxid);
            if (profile != null) {
                s.setSmallHeadURL(profile.smallHeadURL);
                s.setBigHeadURL(profile.bigHeadURL);
                if (profile.remark != null && !profile.remark.isEmpty()) {
                    s.setName(profile.remark);
                } else if (profile.nickName != null && !profile.nickName.isEmpty()) {
                    s.setName(profile.nickName);
                }
            }
            if (s.getName() == null) {
                s.setName(s.getNickName() != null ? s.getNickName() : wxid);
            }
            s.setLastMessage(s.getContent() != null ? s.getContent() : "");
            if (s.getLastTime() == 0) s.setLastTime(s.getNTime());
        } catch (SQLException e) {
            log.debug("填充会话信息失败: {}", wxid, e);
        }
    }

    public void deleteSession(String username) throws SQLException {
        String dbPath = router.getSessionDBPath();
        Connection conn = pool.getConnection(dbPath);

        String sql;
        if (pool.isTableExist(dbPath, "SessionTable")) {
            sql = "DELETE FROM SessionTable WHERE username = ?";
        } else {
            sql = "DELETE FROM Session WHERE strUsrName = ?";
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.executeUpdate();
        }
    }

    // ==================== 消息 ====================

    public List<WeChatMessage> getMessages(String talker, int msgType, String sender,
                                           LocalDateTime start, LocalDateTime end,
                                           int limit, int offset) throws SQLException {
        if (start == null) start = LocalDateTime.of(1970, 1, 1, 0, 0);
        if (end == null) end = LocalDateTime.now();

        List<DbRouter.RouteResult> targets = router.resolve(start, end, talker);
        if (targets.isEmpty()) return Collections.emptyList();

        List<WeChatMessage> all = new CopyOnWriteArrayList<>();

        for (DbRouter.RouteResult target : targets) {
            Connection conn;
            try {
                conn = pool.getConnection(target.getFilePath());
            } catch (SQLException e) {
                log.warn("无法连接分片: {}", target.getFilePath());
                continue;
            }

            String tableName = resolveMessageTableName(conn, target.getFilePath(), target.getTalker());
            try {
                if (tableName != null) {
                    all.addAll(queryV4Messages(conn, target.getFilePath(), tableName, talker, msgType, sender, start, end));
                } else if (pool.isTableExist(target.getFilePath(), "MSG")) {
                    all.addAll(queryV3Messages(conn, target, msgType, sender, start, end));
                }
            } catch (SQLException e) {
                log.warn("查询分片失败: {}", target.getFilePath(), e);
            }
        }

        // 对齐 Go：全分片合并后按真实时间线排序，再分页
        all.sort(messageTimelineComparator());
        List<WeChatMessage> deduped = deduplicateMessages(all);
        List<WeChatMessage> paged = paginateMessages(deduped, limit, offset);
        enrichMessageProfiles(paged);
        return paged;
    }

    private Comparator<WeChatMessage> messageTimelineComparator() {
        return Comparator
            .comparingLong((WeChatMessage m) -> normalizeUnixTimestamp(m.getTimestamp()))
            .thenComparingLong(m -> m.getSortSeq() > 0 ? m.getSortSeq() : m.getSeq())
            .thenComparingLong(WeChatMessage::getServerId);
    }

    private String tableNameForTalker(String talker) {
        if (talker == null || talker.isEmpty()) return null;
        return "Msg_" + router.md5Hex(talker);
    }

    private String resolveMessageTableName(Connection conn, String dbPath, String talker) {
        String expected = tableNameForTalker(talker);
        if (expected == null) {
            return null;
        }
        if (pool.isTableExist(dbPath, expected)) {
            return expected;
        }
        // 对齐 Go：V4 严格只认 Msg_{md5(talker)}，不做模糊候选表兜底
        return null;
    }

    private List<String> findCandidateMessageTables(Connection conn) {
        List<String> tables = new ArrayList<>();
        String sql = """
            SELECT name
            FROM sqlite_master
            WHERE type='table'
              AND (
                    name LIKE 'Msg_%'
                 OR name LIKE 'msg_%'
                 OR name LIKE 'BizMsg_%'
                 OR name LIKE 'biz_msg_%'
              )
            ORDER BY name
            """;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        } catch (SQLException ignored) {
        }
        return tables;
    }

    private Long findTalkerRowId(Connection conn, String dbPath, String talker) {
        if (!pool.isTableExist(dbPath, "Name2Id")) {
            return null;
        }
        String sql = "SELECT rowid FROM Name2Id WHERE user_name = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, talker);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException ignored) {
        }
        return null;
    }

    private boolean tableHasSenderRows(Connection conn, String tableName, long senderRowId) {
        String sql = "SELECT 1 FROM " + tableName + " WHERE real_sender_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, senderRowId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

    private List<WeChatMessage> queryV4Messages(Connection conn, String dbPath, String tableName, String talker,
                                                int msgType, String sender,
                                                LocalDateTime start, LocalDateTime end) throws SQLException {
        boolean hasName2Id = pool.isTableExist(dbPath, "Name2Id");
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.sort_seq, m.server_id, m.local_type, ");
        sql.append(hasName2Id ? "n.user_name" : "''");
        sql.append(", m.create_time, ");
        sql.append("m.message_content, m.compress_content, m.packed_info_data, m.status ");
        sql.append("FROM ").append(tableName).append(" m ");
        if (hasName2Id) {
            sql.append("LEFT JOIN Name2Id n ON m.real_sender_id = n.rowid ");
        }
        sql.append("WHERE ((m.create_time >= ? AND m.create_time <= ?) OR (m.create_time >= ? AND m.create_time <= ?))");

        List<Object> args = new ArrayList<>();
        long startSec = start.toEpochSecond(ZoneId.systemDefault().getRules().getOffset(start));
        long endSec = end.toEpochSecond(ZoneId.systemDefault().getRules().getOffset(end));
        args.add(startSec);
        args.add(endSec);
        args.add(startSec * 1000);
        args.add(endSec * 1000);

        if (msgType > 0) {
            sql.append(" AND m.local_type = ?");
            args.add(msgType);
        }
        sql.append(" ORDER BY m.sort_seq ASC");

        List<WeChatMessage> msgs = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                byte[] messageContent = rs.getBytes(6);
                byte[] compressContent = rs.getBytes(7);
                byte[] packedInfoData = rs.getBytes(8);
                byte[] mergedPackedInfo = (packedInfoData == null || packedInfoData.length == 0) ? messageContent : packedInfoData;
                WeChatMessage m = WeChatMessage.builder()
                    .sortSeq(rs.getLong(1))
                    .serverId(rs.getLong(2))
                    .type(rs.getInt(3))
                    .sender(rs.getString(4))
                    .timestamp(normalizeUnixTimestamp(rs.getLong(5)))
                    .content(resolveV4Content(rs.getInt(3), messageContent, compressContent, mergedPackedInfo))
                    .compressContent(compressContent)
                    .packedInfoData(mergedPackedInfo)
                    .status(rs.getInt(9))
                    .seq(rs.getLong(1))
                    .talker(talker)
                    .isChatRoom(talker.contains("@chatroom") || talker.contains("@openim"))
                    .build();
                enrichImageMessageFromPackedInfo(m);
                applyGoV4SenderSemantics(m);

                // 解析 XML 内容
                if (m.getType() != 1 && m.getContent() != null) {
                    parseXmlContent(m);
                }
                enrichEmojiMessageFromRaw(m);

                if (sender == null || sender.isEmpty() || sender.equals(m.getSender())) {
                    msgs.add(m);
                }
            }
        }

        return msgs;
    }

    private List<WeChatMessage> queryV3Messages(Connection conn, DbRouter.RouteResult target,
                                                  int msgType, String sender,
                                                  LocalDateTime start, LocalDateTime end) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT MsgSvrID, Sequence, CreateTime, StrTalker, IsSender, ");
        sql.append("Type, SubType, StrContent, CompressContent, BytesExtra ");
        sql.append("FROM MSG WHERE Sequence >= ? AND Sequence <= ?");

        List<Object> args = new ArrayList<>();
        args.add(start.toEpochSecond(ZoneId.systemDefault().getRules().getOffset(start)));
        args.add(end.toEpochSecond(ZoneId.systemDefault().getRules().getOffset(end)));

        if (msgType > 0) {
            sql.append(" AND Type = ?");
            args.add(msgType);
        }
        if (target.getTalkerId() > 0) {
            sql.append(" AND TalkerId = ?");
            args.add(target.getTalkerId());
        } else if (target.getTalker() != null && !target.getTalker().isEmpty()) {
            sql.append(" AND StrTalker = ?");
            args.add(target.getTalker());
        }
        sql.append(" ORDER BY Sequence ASC");

        List<WeChatMessage> msgs = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                byte[] strContentRaw = rs.getBytes(8);
                byte[] compressContent = rs.getBytes(9);
                byte[] bytesExtra = rs.getBytes(10);
                byte[] mergedPackedInfo = mergeBinarySources(bytesExtra, strContentRaw, compressContent);
                String rawContent = rs.getString(8);
                if ((rawContent == null || rawContent.isBlank()) && strContentRaw != null && strContentRaw.length > 0) {
                    rawContent = bytesToUtf8(strContentRaw);
                    if ((rawContent == null || rawContent.isBlank()) && compressContent != null && compressContent.length > 0) {
                        rawContent = bytesToUtf8(compressContent);
                    }
                }
                WeChatMessage m = WeChatMessage.builder()
                    .serverId(rs.getLong(1))
                    .seq(rs.getLong(2))
                    .timestamp(rs.getLong(3) / 1000) // V3 CreateTime 毫秒
                    .talker(rs.getString(4))
                    .isSelf(rs.getInt(5) == 1)
                    .type(rs.getInt(6))
                    .subType(rs.getInt(7))
                    .content(decompressIfNeeded(rawContent, compressContent))
                    .compressContent(compressContent)
                    .packedInfoData(mergedPackedInfo)
                    .isChatRoom(rs.getString(4).contains("@chatroom"))
                    .build();

                if (sender == null || sender.isEmpty() || sender.equals(m.getSender())) {
                    if (m.getType() != 1 && m.getContent() != null) {
                        parseXmlContent(m);
                    }
                    enrichEmojiMessageFromRaw(m);
                    msgs.add(m);
                }
            }
        }

        return msgs;
    }

    // ==================== 联系人 ====================

    public List<WeChatContact> getContacts(String keyword, int limit, int offset) throws SQLException {
        String dbPath = router.getContactDBPath();
        Connection conn = pool.getConnection(dbPath);

        if (pool.isTableExist(dbPath, "contact")) {
            return queryV4Contacts(conn, keyword, limit, offset);
        }
        return queryV3Contacts(conn, keyword, limit, offset);
    }

    private List<WeChatContact> queryV4Contacts(Connection conn, String keyword, int limit, int offset)
            throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT username, local_type, alias, remark, nick_name, ");
        sql.append("COALESCE(small_head_url,''), COALESCE(big_head_url,'') FROM contact");

        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" WHERE username LIKE ? OR alias LIKE ? OR remark LIKE ? OR nick_name LIKE ?");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        sql.append(" ORDER BY username");
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
            if (offset > 0) sql.append(" OFFSET ").append(offset);
        }

        List<WeChatContact> contacts = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                contacts.add(WeChatContact.builder()
                    .username(rs.getString(1))
                    .localType(rs.getInt(2))
                    .alias(rs.getString(3))
                    .remark(rs.getString(4))
                    .nickName(rs.getString(5))
                    .smallHeadURL(rs.getString(6))
                    .bigHeadURL(rs.getString(7))
                    .build());
            }
        }
        return contacts;
    }

    private List<WeChatContact> queryV3Contacts(Connection conn, String keyword, int limit, int offset)
            throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT UserName, Alias, Remark, NickName, Reserved1, ");
        sql.append("COALESCE(SmallHeadImgUrl,''), COALESCE(BigHeadImgUrl,'') FROM Contact");

        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" WHERE UserName LIKE ? OR Alias LIKE ? OR Remark LIKE ? OR NickName LIKE ?");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        sql.append(" ORDER BY UserName");
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
            if (offset > 0) sql.append(" OFFSET ").append(offset);
        }

        List<WeChatContact> contacts = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                contacts.add(WeChatContact.builder()
                    .userName(rs.getString(1))
                    .alias(rs.getString(2))
                    .remark(rs.getString(3))
                    .nickName(rs.getString(4))
                    .reserved1(rs.getString(5))
                    .smallHeadURL(rs.getString(6))
                    .bigHeadURL(rs.getString(7))
                    .build());
            }
        }
        return contacts;
    }

    // ==================== 媒体 ====================

    public WeChatMedia getMedia(String mediaType, String key) throws SQLException {
        if (key == null || key.isEmpty()) return null;

        String dbPath = router.getMediaDBPath(DbRouter.MediaType.valueOf(mediaType.toUpperCase()));
        Connection conn = pool.getConnection(dbPath);

        // V4
        String v4Table = mediaType.equals("voice") ? null :
            (mediaType.equals("image") || mediaType.equals("image_merge"))
                ? (pool.isTableExist(dbPath, "image_hardlink_info_v4")
                    ? "image_hardlink_info_v4" : "image_hardlink_info_v3")
                : mediaType.equals("video")
                    ? (pool.isTableExist(dbPath, "video_hardlink_info_v4")
                        ? "video_hardlink_info_v4" : "video_hardlink_info_v3")
                    : (pool.isTableExist(dbPath, "file_hardlink_info_v4")
                        ? "file_hardlink_info_v4" : "file_hardlink_info_v3");

        if (v4Table != null && pool.isTableExist(dbPath, v4Table)) {
            return queryV4Media(conn, v4Table, mediaType, key);
        }

        // V3
        return queryV3Media(conn, mediaType, key);
    }

    private WeChatMedia queryV4Media(Connection conn, String table, String mediaType, String key)
            throws SQLException {
        String sql = String.format("""
            SELECT f.md5, f.file_name, f.file_size, f.modify_time, f.extra_buffer,
                   IFNULL(d1.username,''), IFNULL(d2.username,'')
            FROM %s f
            LEFT JOIN dir2id d1 ON d1.rowid = f.dir1
            LEFT JOIN dir2id d2 ON d2.rowid = f.dir2
            WHERE f.md5 = ? OR f.file_name LIKE ? || '%%'
            """, table);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, key);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                WeChatMedia m = WeChatMedia.builder()
                    .key(rs.getString(1))
                    .name(rs.getString(2))
                    .path(buildV4MediaPath(mediaType, rs.getString(6), rs.getString(7), rs.getString(2), rs.getBytes(5)))
                    .size(rs.getLong(3))
                    .modifyTime(rs.getLong(4))
                    .extraBuffer(rs.getBytes(5))
                    .dir1(rs.getString(6))
                    .dir2(rs.getString(7))
                    .type(mediaType)
                    .build();
                if ("image".equals(mediaType) && m.getName() != null
                    && m.getName().endsWith("_h.dat")) {
                    return m;
                }
                if (!rs.isLast()) continue; // 取最后一个
                return m;
            }
        }
        return null;
    }

    private WeChatMedia queryV3Media(Connection conn, String mediaType, String key) throws SQLException {
        String[] tables = switch (mediaType) {
            case "image" -> new String[]{"HardLinkImageAttribute", "HardLinkImageID"};
            case "video" -> new String[]{"HardLinkVideoAttribute", "HardLinkVideoID"};
            case "file" -> new String[]{"HardLinkFileAttribute", "HardLinkFileID"};
            default -> null;
        };
        if (tables == null) return null;

        byte[] md5key;
        try {
            md5key = hexToBytes(key);
        } catch (Exception e) {
            return null;
        }

        String sql = String.format("""
            SELECT a.FileName, a.ModifyTime, IFNULL(d1.Dir,'') AS Dir1, IFNULL(d2.Dir,'') AS Dir2
            FROM %s a
            LEFT JOIN %s d1 ON a.DirID1 = d1.DirId
            LEFT JOIN %s d2 ON a.DirID2 = d2.DirId
            WHERE a.Md5 = ?
            """, tables[0], tables[1], tables[1]);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, md5key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return WeChatMedia.builder()
                    .name(rs.getString(1))
                    .path(buildV3MediaPath(mediaType, rs.getString(3), rs.getString(4), rs.getString(1)))
                    .modifyTime(rs.getLong(2))
                    .dir1(rs.getString(3))
                    .dir2(rs.getString(4))
                    .type(mediaType)
                    .key(key)
                    .build();
            }
        }
        return null;
    }

    private String buildV4MediaPath(String mediaType, String dir1, String dir2, String name, byte[] extraBuffer) {
        String safeDir1 = dir1 == null ? "" : dir1;
        String safeDir2 = dir2 == null ? "" : dir2;
        String safeName = name == null ? "" : name;
        String recId = parseRecId(extraBuffer);
        return switch (mediaType) {
            case "image", "image_merge" -> !recId.isEmpty()
                ? Paths.get("msg", "attach", safeDir1, safeDir2, "Rec", recId, "Img", safeName).toString()
                : Paths.get("msg", "attach", safeDir1, safeDir2, "Img", safeName).toString();
            case "video" -> Paths.get("msg", "video", safeDir1, safeName).toString();
            case "file" -> Paths.get("msg", "file", safeDir1, safeName).toString();
            default -> safeName;
        };
    }

    private String buildV3MediaPath(String mediaType, String dir1, String dir2, String name) {
        String safeDir1 = dir1 == null ? "" : dir1;
        String safeDir2 = dir2 == null ? "" : dir2;
        String safeName = name == null ? "" : name;
        return switch (mediaType) {
            case "image" -> Paths.get("msg", "attach", safeDir1, safeDir2, "Img", safeName).toString();
            case "video" -> Paths.get("msg", "video", safeDir1, safeName).toString();
            case "file" -> Paths.get("msg", "file", safeDir1, safeName).toString();
            default -> safeName;
        };
    }

    private String parseRecId(byte[] extraBuffer) {
        if (extraBuffer == null || extraBuffer.length == 0) {
            return "";
        }
        String ascii = new String(extraBuffer, StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?i)\\b([a-f0-9]{16,32})\\b")
            .matcher(ascii);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    // ==================== 分析 ====================

    public List<HourlyStat> getHourlyActivity(String talker) throws SQLException {
        List<DbRouter.RouteResult> targets = router.resolve(LocalDateTime.now().minusYears(1), LocalDateTime.now(), talker);
        int[] hourly = new int[24];

        for (DbRouter.RouteResult target : targets) {
            Connection conn;
            try { conn = pool.getConnection(target.getFilePath()); }
            catch (SQLException e) { continue; }

            String table = tableNameForTalker(talker);
            if (table == null || !pool.isTableExist(target.getFilePath(), table)) continue;

            try {
                String sql = "SELECT CAST(strftime('%H', datetime(create_time, 'unixepoch', 'localtime')) AS INTEGER) AS h, COUNT(*) FROM "
                    + table + " GROUP BY h";
                try (Statement ps = conn.createStatement();
                     ResultSet rs = ps.executeQuery(sql)) {
                    while (rs.next()) {
                        hourly[rs.getInt(1)] += rs.getInt(2);
                    }
                }
            } catch (SQLException e) {
                log.debug("时段统计失败: {}", target.getFilePath());
            }
        }

        return java.util.Arrays.stream(hourly).mapToObj(h ->
            HourlyStat.builder().hour(h).count(hourly[h]).build()
        ).collect(Collectors.toList());
    }

    public String getCurrentUserWxid() {
        if (currentUserWxid != null) return currentUserWxid;
        List<DbRouter.RouteResult> shards = router.resolve(LocalDateTime.now(), LocalDateTime.now(), null);
        for (DbRouter.RouteResult shard : shards) {
            try {
                Connection conn = pool.getConnection(shard.getFilePath());
                String table = findFirstMsgTable(conn);
                if (table == null) continue;

                String sql = String.format("""
                    SELECT n.user_name FROM %s m
                    JOIN Name2Id n ON m.real_sender_id = n.rowid
                    WHERE m.status = 2 AND n.user_name IS NOT NULL AND n.user_name != ''
                    LIMIT 1
                    """, table);
                try (Statement ps = conn.createStatement();
                     ResultSet rs = ps.executeQuery(sql)) {
                    if (rs.next()) {
                        currentUserWxid = rs.getString(1);
                        return currentUserWxid;
                    }
                }
            } catch (SQLException ignored) {}
        }
        return "";
    }

    private String findFirstMsgTable(Connection conn) throws SQLException {
        try (Statement ps = conn.createStatement();
             ResultSet rs = ps.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'Msg_%%' LIMIT 1")) {
            if (rs.next()) return rs.getString(1);
        }
        return null;
    }

    // ==================== 内部工具 ====================

    /** 填充联系人信息（头像+昵称） */
    private ContactProfile getContactProfile(String wxid) throws SQLException {
        String dbPath = router.getContactDBPath();
        Connection contactConn = pool.getConnection(dbPath);

        String sql;
        if (pool.isTableExist(dbPath, "contact")) {
            sql = "SELECT remark, nick_name, small_head_url, big_head_url FROM contact WHERE username = ?";
        } else {
            sql = "SELECT Remark, NickName, SmallHeadImgUrl, BigHeadImgUrl FROM Contact WHERE UserName = ?";
        }

        try (PreparedStatement ps = contactConn.prepareStatement(sql)) {
            ps.setString(1, wxid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new ContactProfile(
                    rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4));
            }
        }
        return null;
    }

    private ContactProfile getContactProfileCached(String wxid) {
        if (wxid == null || wxid.isBlank()) {
            return null;
        }
        if (contactProfileCache.containsKey(wxid)) {
            return contactProfileCache.get(wxid);
        }
        if (missingContactProfileCache.contains(wxid)) {
            return null;
        }
        try {
            ContactProfile profile = getContactProfile(wxid);
            if (profile != null) {
                contactProfileCache.put(wxid, profile);
                return profile;
            }
            missingContactProfileCache.add(wxid);
        } catch (SQLException ignored) {
        }
        return null;
    }

    private void enrichMessageProfiles(List<WeChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String selfWxid = getCurrentUserWxid();
        ContactProfile selfProfile = getContactProfileCached(selfWxid);
        for (WeChatMessage m : messages) {
            String talker = m.getTalker() == null ? "" : m.getTalker().trim();
            ContactProfile talkerProfile = getContactProfileCached(talker);
            if (talkerProfile != null) {
                if (m.getTalkerName() == null || m.getTalkerName().isBlank()) {
                    m.setTalkerName(displayName(talkerProfile, talker));
                }
            } else if ((m.getTalkerName() == null || m.getTalkerName().isBlank()) && !talker.isBlank()) {
                m.setTalkerName(talker);
            }

            String senderWxid = m.getSender();
            if (senderWxid == null || senderWxid.isBlank()) {
                senderWxid = m.isSelf() ? selfWxid : talker;
                if (senderWxid != null && !senderWxid.isBlank()) {
                    m.setSender(senderWxid);
                }
            }

            ContactProfile senderProfile = getContactProfileCached(senderWxid);
            if (senderProfile == null && m.isSelf()) {
                senderProfile = selfProfile;
            }
            if (senderProfile != null && (m.getSenderName() == null || m.getSenderName().isBlank())) {
                m.setSenderName(displayName(senderProfile, senderWxid));
            }
            if ((m.getSenderName() == null || m.getSenderName().isBlank()) && senderWxid != null && !senderWxid.isBlank()) {
                m.setSenderName(senderWxid);
            }

            ContactProfile avatarProfile = senderProfile != null ? senderProfile : talkerProfile;
            if (m.getSmallHeadURL() == null || m.getSmallHeadURL().isBlank()) {
                if (avatarProfile != null && avatarProfile.smallHeadURL() != null) {
                    m.setSmallHeadURL(avatarProfile.smallHeadURL());
                }
            }
            if (m.getBigHeadURL() == null || m.getBigHeadURL().isBlank()) {
                if (avatarProfile != null && avatarProfile.bigHeadURL() != null) {
                    m.setBigHeadURL(avatarProfile.bigHeadURL());
                }
            }
        }
    }

    private String displayName(ContactProfile profile, String fallback) {
        if (profile == null) {
            return fallback == null ? "" : fallback;
        }
        if (profile.remark() != null && !profile.remark().isBlank()) {
            return profile.remark();
        }
        if (profile.nickName() != null && !profile.nickName().isBlank()) {
            return profile.nickName();
        }
        return fallback == null ? "" : fallback;
    }

    /** 压缩内容解压缩（V3 格式） */
    private String decompressIfNeeded(String content, byte[] compressed) {
        if (content != null && !content.isEmpty()) return content;
        if (compressed == null || compressed.length == 0) return "";

        try {
            // 尝试 LZ4 解压
            return new String(decompressLz4(compressed), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new String(compressed, StandardCharsets.UTF_8);
        }
    }

    private byte[] decompressIfNeeded(byte[] content, byte[] compressed) {
        if (content != null && content.length > 0) return content;
        return decompressIfNeeded((String) null, compressed).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * V4 中 message_content 可能是二进制（尤其 type=49）。
     * 优先返回可读文本，避免把原始二进制直接序列化成乱码。
     */
    private String resolveV4Content(int msgType, byte[] messageContent, byte[] compressed, byte[] packedInfoData) {
        String direct = bytesToUtf8(messageContent);
        if (isLikelyReadableText(direct)) {
            return direct;
        }

        String fromCompressed = decompressIfNeeded((String) null, compressed);
        if (isLikelyReadableText(fromCompressed)) {
            return fromCompressed;
        }

        String xml = extractXmlSnippet(messageContent);
        if (!xml.isEmpty()) {
            return xml;
        }
        xml = extractXmlSnippet(compressed);
        if (!xml.isEmpty()) {
            return xml;
        }
        xml = extractXmlSnippet(packedInfoData);
        if (!xml.isEmpty()) {
            return xml;
        }

        // 对分享等非文本消息，空字符串比乱码更安全
        // 非文本消息返回空；文本/系统消息若不可读也返回空，避免返回大段二进制乱码
        if (msgType != 1 && msgType != 10000) {
            return "";
        }
        return "";
    }

    private boolean isLikelyReadableText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.startsWith("<")) {
            return true;
        }
        int checked = 0;
        int bad = 0;
        for (int i = 0; i < text.length() && checked < 512; i++, checked++) {
            char c = text.charAt(i);
            if (c == '\uFFFD') {
                bad++;
                continue;
            }
            if (Character.isISOControl(c) && !Character.isWhitespace(c)) {
                bad++;
            }
        }
        return checked > 0 && ((double) bad / checked) < 0.08;
    }

    private String extractXmlSnippet(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        String s = new String(data, StandardCharsets.UTF_8);
        int start = s.indexOf("<msg");
        if (start < 0) start = s.indexOf("<appmsg");
        if (start < 0) return "";
        int end = s.indexOf("</msg>", start);
        if (end >= 0) {
            return s.substring(start, end + "</msg>".length());
        }
        end = s.indexOf("</appmsg>", start);
        if (end >= 0) {
            return s.substring(start, end + "</appmsg>".length());
        }
        return "";
    }

    private void enrichImageMessageFromPackedInfo(WeChatMessage m) {
        if (m == null || m.getType() != 3) {
            return;
        }
        String md5 = extractHexMd5(m.getPackedInfoData());
        if (md5.isEmpty()) {
            return;
        }
        m.setThumb(md5);
        Map<String, Object> contents = m.getContents() == null ? new HashMap<>() : new HashMap<>(m.getContents());
        contents.putIfAbsent("md5", md5);

        String talker = m.getTalker() == null ? "" : m.getTalker();
        if (!talker.isBlank() && m.getTimestamp() > 0) {
            long ts = m.getTimestamp();
            long seconds = ts > 9_999_999_999L ? ts / 1000 : ts;
            LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.systemDefault());
            String month = String.format("%04d-%02d", dt.getYear(), dt.getMonthValue());
            String talkerMd5 = router.md5Hex(talker);
            if (talkerMd5 != null && !talkerMd5.isBlank()) {
                String path = Paths.get("msg", "attach", talkerMd5, month, "Img", md5).toString();
                contents.putIfAbsent("path", path);
            }
        }

        m.setContents(contents);
    }

    private String extractHexMd5(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        String s = new String(data, StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)\\b([a-f0-9]{32})\\b").matcher(s);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    /**
     * 对齐 Go 的 V4 sender/self 判定逻辑：
     * 1) isSelf = status==2 || (非群聊且 talker!=sender)
     * 2) 群聊中若 content 形如 "wxid_xxx:\n正文"，则 sender=前缀，isSelf=false
     * 3) 群聊中无前缀且非系统消息，视为自己发送
     */
    private void applyGoV4SenderSemantics(WeChatMessage m) {
        if (m == null) {
            return;
        }
        boolean isChatRoom = m.isChatRoom();
        String talker = m.getTalker() == null ? "" : m.getTalker();
        String sender = m.getSender() == null ? "" : m.getSender();
        boolean isSelf = m.getStatus() == 2 || (!isChatRoom && !sender.isBlank() && !Objects.equals(talker, sender));

        if (isChatRoom) {
            String content = m.getContent() == null ? "" : m.getContent();
            int split = content.indexOf(":\n");
            if (split > 0) {
                String realSender = content.substring(0, split).trim();
                if (!realSender.isEmpty()) {
                    m.setSender(realSender);
                    m.setContent(content.substring(split + 2));
                    isSelf = false;
                }
            } else if (m.getType() != 10000) {
                isSelf = true;
            }
        }

        m.setSelf(isSelf);
    }

    private List<WeChatMessage> deduplicateMessages(List<WeChatMessage> input) {
        LinkedHashMap<String, WeChatMessage> uniq = new LinkedHashMap<>();
        for (WeChatMessage m : input) {
            String key = (m.getTalker() == null ? "" : m.getTalker()) + "|"
                + m.getServerId() + "|"
                + m.getSortSeq() + "|"
                + m.getType() + "|"
                + (m.getSender() == null ? "" : m.getSender());
            uniq.putIfAbsent(key, m);
        }
        return new ArrayList<>(uniq.values());
    }

    private List<WeChatMessage> paginateMessages(List<WeChatMessage> input, int limit, int offset) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyList();
        }
        int start = Math.max(0, offset);
        if (start >= input.size()) {
            return Collections.emptyList();
        }
        int end = (limit <= 0) ? input.size() : Math.min(input.size(), start + limit);
        return new ArrayList<>(input.subList(start, end));
    }

    private String bytesToUtf8(byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private byte[] decompressLz4(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return new byte[0];
        }

        IOException last = null;

        // 0) WeChat 常见格式：前 4 字节小端原始长度 + 原始 LZ4 block 数据
        if (data.length > 8) {
            int originalLen = ((data[0] & 0xFF))
                | ((data[1] & 0xFF) << 8)
                | ((data[2] & 0xFF) << 16)
                | ((data[3] & 0xFF) << 24);
            if (originalLen > 0 && originalLen < 64 * 1024 * 1024) {
                try {
                    byte[] dest = new byte[originalLen];
                    LZ4Factory.fastestInstance()
                        .fastDecompressor()
                        .decompress(data, 4, dest, 0, originalLen);
                    if (dest.length > 0) return dest;
                } catch (Exception e) {
                    last = new IOException("raw lz4 decode failed", e);
                }
            }
        }

        // 1) LZ4 frame 格式
        try (ByteArrayInputStream in = new ByteArrayInputStream(data);
             LZ4FrameInputStream lz4 = new LZ4FrameInputStream(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            lz4.transferTo(out);
            byte[] decoded = out.toByteArray();
            if (decoded.length > 0) return decoded;
        } catch (IOException e) {
            last = e;
        }

        // 2) LZ4 block 格式
        try (ByteArrayInputStream in = new ByteArrayInputStream(data);
             LZ4BlockInputStream lz4 = new LZ4BlockInputStream(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            lz4.transferTo(out);
            byte[] decoded = out.toByteArray();
            if (decoded.length > 0) return decoded;
        } catch (IOException e) {
            last = e;
        }

        // 3) 部分版本数据是 zlib/deflate
        try (ByteArrayInputStream in = new ByteArrayInputStream(data);
             InflaterInputStream z = new InflaterInputStream(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            z.transferTo(out);
            byte[] decoded = out.toByteArray();
            if (decoded.length > 0) return decoded;
        } catch (IOException e) {
            last = e;
        }

        if (last != null) throw last;
        throw new IOException("unknown compressed format");
    }

    private long normalizeUnixTimestamp(long ts) {
        if (ts > 10_000_000_000L) {
            return ts / 1000;
        }
        return ts;
    }

    /** 解析多媒体 XML 内容 */
    private void parseXmlContent(WeChatMessage m) {
        String xml = m.getContent();
        if (xml != null && !xml.startsWith("<")) {
            xml = extractXmlSnippet(xml);
        }
        if (xml == null || !xml.startsWith("<")) {
            xml = extractXmlSnippet(m.getCompressContent());
        }
        if (xml == null || !xml.startsWith("<")) {
            xml = extractXmlSnippet(m.getPackedInfoData());
        }
        if (xml == null || !xml.startsWith("<")) return;
        if (m.getContent() == null || m.getContent().isBlank()) {
            m.setContent(xml);
        }

        try {
            Map<String, Object> contents = m.getContents() == null ? new HashMap<>() : new HashMap<>(m.getContents());
            if (m.getType() == 3) { // 图片
                String md5 = extractXmlAttr(xml, "md5");
                if (!md5.isEmpty()) {
                    contents.put("md5", md5.toLowerCase(Locale.ROOT));
                    m.setThumb(md5.toLowerCase(Locale.ROOT));
                }
            } else if (m.getType() == 34) { // 语音
                String length = extractXmlAttr(xml, "voicelength");
                if (!length.isEmpty()) {
                    try {
                        contents.put("duration", Integer.parseInt(length));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (m.getType() == 47) { // 表情
                String url = extractXmlAttr(xml, "cdnurl");
                String key = extractXmlAttr(xml, "aeskey");
                String md5 = extractXmlAttr(xml, "md5");
                if (!url.isEmpty()) {
                    contents.put("cdnurl", url);
                    contents.put("url", url);
                }
                if (!key.isEmpty()) {
                    contents.put("aeskey", key);
                    contents.put("key", key);
                }
                if (!md5.isEmpty()) contents.put("md5", md5.toLowerCase(Locale.ROOT));
            } else if (m.getType() == 49) { // 分享/文件/引用
                String title = extractXmlTag(xml, "title");
                String des = extractXmlTag(xml, "des");
                String url = extractXmlTag(xml, "url");
                String appMsgTypeRaw = extractXmlTag(xml, "appmsgtype");
                long totalLen = parseLongSafely(extractXmlTag(xml, "totallen"));
                String fileName = extractXmlTag(xml, "filename");
                String fileExt = extractXmlTag(xml, "fileext");

                if (!title.isEmpty()) contents.put("title", title);
                if (!des.isEmpty()) contents.put("desc", des);
                if (!url.isEmpty()) contents.put("url", url);
                if (totalLen > 0) contents.put("size", totalLen);
                if (!fileName.isEmpty()) contents.put("fileName", fileName);
                if (!fileExt.isEmpty()) contents.put("fileExt", fileExt);

                int appMsgType = 0;
                if (!appMsgTypeRaw.isEmpty()) {
                    try {
                        appMsgType = Integer.parseInt(appMsgTypeRaw.trim());
                        contents.put("appMsgType", appMsgType);
                    } catch (NumberFormatException ignored) {
                    }
                }

                // 让前端能像 Go 一样按 appmsg 子类型渲染
                if (appMsgType > 0 && m.getSubType() == 0) {
                    m.setSubType(appMsgType);
                }
            }
            if (!contents.isEmpty()) {
                m.setContents(contents);
            }
        } catch (Exception e) {
            log.debug("解析 XML 内容失败: {}", xml.substring(0, Math.min(50, xml.length())));
        }
    }

    private String extractXmlAttr(String xml, String attr) {
        if (xml == null || xml.isBlank() || attr == null || attr.isBlank()) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?i)\\b" + java.util.regex.Pattern.quote(attr) + "\\s*=\\s*(['\"])(.*?)\\1")
            .matcher(xml);
        if (matcher.find()) {
            return matcher.group(2).trim();
        }
        return "";
    }

    private String extractXmlTag(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        if (start < 0) return "";
        start += open.length();
        int end = xml.indexOf(close, start);
        if (end < 0 || end <= start) return "";
        String value = xml.substring(start, end).trim();
        if (value.startsWith("<![CDATA[") && value.endsWith("]]>")) {
            value = value.substring(9, value.length() - 3).trim();
        }
        return value;
    }

    private String extractXmlSnippet(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        int start = raw.indexOf("<msg");
        if (start < 0) start = raw.indexOf("<appmsg");
        if (start < 0) return "";
        int end = raw.indexOf("</msg>", start);
        if (end >= 0) return raw.substring(start, end + "</msg>".length());
        end = raw.indexOf("</appmsg>", start);
        if (end >= 0) return raw.substring(start, end + "</appmsg>".length());
        return "";
    }

    private long parseLongSafely(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void enrichEmojiMessageFromRaw(WeChatMessage m) {
        if (m == null || m.getType() != 47) {
            return;
        }
        Map<String, Object> contents = m.getContents() == null ? new HashMap<>() : new HashMap<>(m.getContents());
        boolean hasUrl = hasNonBlank(contents.get("cdnurl")) || hasNonBlank(contents.get("url"));
        boolean hasKey = hasNonBlank(contents.get("aeskey")) || hasNonBlank(contents.get("key"));
        if (hasUrl && hasKey) {
            return;
        }

        String[] candidates = new String[]{
            m.getContent(),
            bytesToUtf8(m.getCompressContent()),
            bytesToUtf8(m.getPackedInfoData())
        };
        for (String raw : candidates) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String text = raw.replace("\\u0026", "&").replace("&amp;", "&");

            if (!hasKey) {
                java.util.regex.Matcher km = java.util.regex.Pattern
                    .compile("(?i)(?:aeskey|cdnthumbaeskey)[\"'=:\\s]+([a-f0-9]{32})")
                    .matcher(text);
                if (km.find()) {
                    String key = km.group(1).toLowerCase(Locale.ROOT);
                    contents.put("aeskey", key);
                    contents.put("key", key);
                    hasKey = true;
                }
            }

            if (!hasUrl) {
                java.util.regex.Matcher um = java.util.regex.Pattern
                    .compile("(?i)(https?://[^\\s\"'<>]+)")
                    .matcher(text);
                if (um.find()) {
                    String url = um.group(1);
                    contents.put("cdnurl", url);
                    contents.put("url", url);
                    hasUrl = true;
                }
            }

            if (hasUrl && hasKey) {
                break;
            }
        }
        if (!contents.isEmpty()) {
            m.setContents(contents);
        }
    }

    private boolean hasNonBlank(Object value) {
        return value instanceof String s && !s.isBlank();
    }

    private byte[] hexToBytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private byte[] mergeBinarySources(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            if (p != null && p.length > 0) {
                total += p.length + 1;
            }
        }
        if (total == 0) {
            return null;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            if (p == null || p.length == 0) continue;
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
            out[pos++] = '\n';
        }
        return java.util.Arrays.copyOf(out, pos);
    }

    // ==================== 内部类 ====================

    private record ContactProfile(String remark, String nickName, String smallHeadURL, String bigHeadURL) {}

}
