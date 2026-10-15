package com.robot.system.permission.service;

import com.robot.system.support.SystemKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 业务事务内递增权威 epoch 并入队；独立调度只投递已提交事件。 */
@Component
@RequiredArgsConstructor
public class CommittedCacheChanges {

    private final PermissionStateStore store;

    /** 创建或返回该类型的明确协作结果。 */
    public void permissionsChanged() {
        requireTransaction();
        long version = store.incrementVersion(PermissionStateStore.PERMISSIONS);
        store.enqueue(PermissionStateStore.PERMISSIONS, SystemKeys.permissionVersion(), version);
    }

    /**
     * 创建或返回该类型的明确协作结果。
     *
     * @param typeCode 字典类型编码
     */
    public void dictionaryChanged(String typeCode) {
        requireTransaction();
        long version = store.incrementVersion(dictionaryScope(typeCode));
        store.enqueue("dictionary", SystemKeys.dictCache(typeCode, version - 1), version);
    }

    /**
     * 创建或返回该类型的明确协作结果。
     *
     * @param typeCode 字典类型编码
     * @return 当前事实派生的结果
     */
    public static String dictionaryScope(String typeCode) {
        return "dictionary:" + typeCode;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("缓存变更必须在业务数据库事务内登记");
        }
    }
}
