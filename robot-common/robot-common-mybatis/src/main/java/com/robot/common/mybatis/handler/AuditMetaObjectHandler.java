package com.robot.common.mybatis.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.security.util.AuthUtil;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充：insert / update 时统一补齐八个治理字段中的可自动部分。
 *
 * <p>规则（wl-skills-bd standards/12 §3）：
 * <ul>
 *   <li>CREATE_USER_NO / CREATE_DATE_TIME / DELETE_FLAG / REVISION 仅插入时填充；</li>
 *   <li>UPDATE_USER_NO / UPDATE_DATE_TIME 插入与更新都填充；</li>
 *   <li>COMPANY_ID 只取 {@code AuthUtil.getLoginCompanyId()}（登录态），未登录写入由租户拦截器兜底拦截；</li>
 *   <li>字段已有值时不覆盖（支持数据修复等显式赋值场景）。</li>
 * </ul>
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        String userNo = currentActor();

        strictInsertFill(metaObject, "companyId", String.class,
                AuthUtil.getLoginCompanyId());
        strictInsertFill(metaObject, "createUserNo", String.class, userNo);
        strictInsertFill(metaObject, "createDateTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updateUserNo", String.class, userNo);
        strictInsertFill(metaObject, "updateDateTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "deleteFlag", Integer.class,
                RobotConstants.DELETE_FLAG_ACTIVE);
        strictInsertFill(metaObject, "revision", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateUserNo", String.class, currentActor());
        strictUpdateFill(metaObject, "updateDateTime", LocalDateTime.class,
                LocalDateTime.now());
    }

    /** 当前操作人工号：登录态取登录名，系统任务取 SYSTEM。 */
    private String currentActor() {
        String username = AuthUtil.getLoginUsername();
        return username == null ? "SYSTEM" : username;
    }
}
