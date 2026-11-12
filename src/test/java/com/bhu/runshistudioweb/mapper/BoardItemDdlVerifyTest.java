package com.bhu.runshistudioweb.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收：事项看板建表脚本的「双份同步」铁律
 *
 * <p>纯文件断言，不碰数据库：db/seg 是 db/init-all.sql 的拆分产物，
 * 只改其中一份会让「clone 下来一键建库」与「单表重建」建出不同的结构。
 *
 * @author shaoshing
 */
class BoardItemDdlVerifyTest {

    private static final String TABLE = "studio_board_item";

    @Test
    @DisplayName("init-all.sql 与 seg/22 两份脚本都建了这张表")
    void bothScriptsCreateTable() throws Exception {
        String initAll = Files.readString(Path.of("db/init-all.sql"));
        String seg = Files.readString(Path.of("db/seg/22-studio_board_item.sql"));

        assertTrue(initAll.contains("CREATE TABLE `" + TABLE + "`"),
                "全量建库脚本必须包含新表");
        assertTrue(seg.contains("CREATE TABLE `" + TABLE + "`"),
                "拆分脚本必须包含新表");
        assertTrue(initAll.contains("共 22 张表") && initAll.contains("22  studio_board_item"),
                "表数与目录注释要跟着改，避免误导部署的人");
    }

    @Test
    @DisplayName("两份脚本的关键列与索引完全一致")
    void scriptsShareColumnsAndIndexes() throws Exception {
        String initAll = Files.readString(Path.of("db/init-all.sql"));
        String seg = Files.readString(Path.of("db/seg/22-studio_board_item.sql"));

        // 只取本表的 CREATE 段，避免误命中别的表里同名列
        String initDdl = tableDdl(initAll);
        String segDdl = tableDdl(seg);
        for (String column : new String[]{
                "title", "description", "status", "assignee_member_id",
                "deadline", "completed_at", "sort_order", "deleted_at",
        }) {
            assertTrue(initDdl.contains("`" + column + "`"), "init-all 缺列 " + column);
            assertTrue(segDdl.contains("`" + column + "`"), "seg 缺列 " + column);
        }
        for (String index : new String[]{
                "idx_status_deadline", "idx_assignee_status", "idx_deadline",
        }) {
            assertTrue(initDdl.contains(index), "init-all 缺索引 " + index);
            assertTrue(segDdl.contains(index), "seg 缺索引 " + index);
        }
    }

    /** 从全量脚本里截出本表的 CREATE TABLE 段（到分号结束） */
    private String tableDdl(String script) {
        int start = script.indexOf("CREATE TABLE `" + TABLE + "`");
        assertTrue(start >= 0, "找不到 CREATE TABLE " + TABLE);
        int end = script.indexOf(';', start);
        return script.substring(start, end < 0 ? script.length() : end);
    }
}
