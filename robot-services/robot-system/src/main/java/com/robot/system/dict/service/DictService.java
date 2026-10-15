package com.robot.system.dict.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.dict.DictionarySaveRequest;
import com.robot.system.api.dto.dict.DictionaryStatusRequest;
import com.robot.system.api.entity.SysDictItem;
import com.robot.system.api.entity.SysDictType;
import com.robot.system.api.vo.dict.DictNode;
import com.robot.system.dict.mapper.SysDictItemMapper;
import com.robot.system.dict.mapper.SysDictTypeMapper;
import com.robot.system.permission.service.CommittedCacheChanges;
import com.robot.system.permission.service.GlobalDirectoryPolicy;
import com.robot.system.permission.service.PermissionStateStore;
import com.robot.system.support.SystemKeys;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 字典管理：类型行锁保护关联写，数据库 epoch 保证已提交数据与缓存一致。 */
@Service
@RequiredArgsConstructor
public class DictService extends ServiceImpl<SysDictTypeMapper, SysDictType> {
    private static final Duration DICT_TTL = Duration.ofMinutes(30);
    private final SysDictItemMapper dictItemMapper;
    private final RedisHelper redisHelper;
    private final PermissionStateStore cacheState;
    private final CommittedCacheChanges cacheChanges;
    private final Validator validator;
    private final GlobalDirectoryPolicy directoryPolicy;

    /**
     * 管理视图包含停用定义，业务取值接口单独过滤类型和项的状态。
     *
     * @return 当前事实派生的结果
     */
    public List<DictNode> dictTree() {
        List<SysDictType> types =
                list(
                        new LambdaQueryWrapper<SysDictType>()
                                .orderByAsc(SysDictType::getTypeCode)
                                .orderByAsc(SysDictType::getId));
        Map<String, List<DictNode>> items =
                dictItemMapper
                        .selectList(
                                new LambdaQueryWrapper<SysDictItem>()
                                        .orderByAsc(SysDictItem::getSortNo)
                                        .orderByAsc(SysDictItem::getId))
                        .stream()
                        .collect(
                                Collectors.groupingBy(
                                        SysDictItem::getTypeId,
                                        Collectors.mapping(DictNode::ofItem, Collectors.toList())));
        return types.stream()
                .map(type -> DictNode.ofType(type, items.getOrDefault(type.getId(), List.of())))
                .toList();
    }

    /**
     * 数据库版本决定缓存键，停用或不存在的类型返回空集合。
     *
     * @param typeCode 字典类型编码
     * @return 当前事实派生的结果
     */
    public List<Map<String, String>> itemsByCode(String typeCode) {
        ServiceAssert.isTrue(
                typeCode != null && typeCode.matches("[a-zA-Z][a-zA-Z0-9_.:-]{0,59}"),
                ResultCode.PARAM_INVALID,
                "字典类型编码不合法");
        long version = cacheState.currentVersion(CommittedCacheChanges.dictionaryScope(typeCode));
        Map<?, ?> payload =
                redisHelper.getOrLoad(
                        SystemKeys.dictCache(typeCode, version),
                        Map.class,
                        DICT_TTL,
                        () -> loadItems(typeCode));
        if (payload == null || !(payload.get("items") instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, String>> result = new ArrayList<>(items.size());
        for (Object raw : items) {
            if (raw instanceof Map<?, ?> item
                    && item.get("label") instanceof String label
                    && item.get("value") instanceof String value) {
                result.add(Map.of("label", label, "value", value));
            }
        }
        return result;
    }

    // 字典允许可空标签/值；每项独立Map保持缓存行隔离，不能复用可变payload。
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private Map<String, Object> loadItems(String typeCode) {
        SysDictType type =
                getOne(
                        new LambdaQueryWrapper<SysDictType>()
                                .eq(SysDictType::getTypeCode, typeCode)
                                .eq(SysDictType::getStatusFlag, 1));
        if (type == null) {
            return null;
        }
        List<Map<String, String>> items = new ArrayList<>();
        for (SysDictItem item :
                dictItemMapper.selectList(
                        new LambdaQueryWrapper<SysDictItem>()
                                .eq(SysDictItem::getTypeId, type.getId())
                                .eq(SysDictItem::getStatusFlag, 1)
                                .orderByAsc(SysDictItem::getSortNo)
                                .orderByAsc(SysDictItem::getId))) {
            HashMap<String, String> entry = new HashMap<>();
            entry.put("label", item.getItemLabel());
            entry.put("value", item.getItemValue());
            items.add(entry);
        }
        HashMap<String, Object> payload = new HashMap<>();
        payload.put("items", items);
        return payload;
    }

    /**
     * 创建定义与缓存版本、事件同事务提交；数据库唯一约束处理并发重名。
     *
     * @param request 该用例接收的白名单请求
     * @return 当前事实派生的结果
     */
    @Transactional(rollbackFor = Exception.class)
    public String create(DictionarySaveRequest request) {
        cacheState.lockMenuTree();
        cacheState.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        directoryPolicy.requirePlatformAdministrator();
        validate(request);
        if ("type".equals(request.getType())) {
            ensureTypeCodeAvailable(request.effectiveCode(), null);
            SysDictType type = new SysDictType();
            type.setTypeCode(request.effectiveCode());
            type.setTypeName(request.getName().trim());
            type.setRemark(request.getRemark());
            type.setStatusFlag(
                    request.getStatus() == null ? Integer.valueOf(1) : request.getStatus());
            ServiceAssert.isTrue(save(type), ResultCode.DATA_CONCURRENT_CONFLICT);
            cacheChanges.dictionaryChanged(type.getTypeCode());
            return type.getId();
        }
        SysDictType owner = lockType(request.getParentId());
        ServiceAssert.isTrue(
                Integer.valueOf(1).equals(owner.getStatusFlag()),
                ResultCode.PARAM_INVALID,
                "请先启用所属字典类型");
        ensureItemAvailable(owner.getId(), request.effectiveCode(), request.effectiveValue(), null);
        SysDictItem item = new SysDictItem();
        applyItem(item, request);
        ServiceAssert.isTrue(dictItemMapper.insert(item) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        cacheChanges.dictionaryChanged(owner.getTypeCode());
        return item.getId();
    }

    /**
     * 按客户端 revision 更新；移动字典项时按 ID 顺序锁定两侧类型。
     *
     * @param id 记录ID
     * @param request 该用例接收的白名单请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(String id, DictionarySaveRequest request) {
        cacheState.lockMenuTree();
        cacheState.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        directoryPolicy.requirePlatformAdministrator();
        validate(request);
        if ("type".equals(request.getType())) {
            SysDictType type = lockType(id);
            requireRevision(type.getRevision(), request.getRevision());
            String previousCode = type.getTypeCode();
            ensureTypeCodeAvailable(request.effectiveCode(), id);
            type.setTypeCode(request.effectiveCode());
            type.setTypeName(request.getName().trim());
            type.setRemark(request.getRemark());
            if (request.getStatus() != null) {
                type.setStatusFlag(request.getStatus());
            }
            ServiceAssert.isTrue(updateById(type), ResultCode.DATA_CONCURRENT_CONFLICT);
            cacheChanges.dictionaryChanged(previousCode);
            if (!previousCode.equals(type.getTypeCode())) {
                cacheChanges.dictionaryChanged(type.getTypeCode());
            }
            return;
        }
        SysDictItem item =
                ServiceAssert.notNull(dictItemMapper.selectById(id), ResultCode.DICT_NOT_FOUND);
        Map<String, SysDictType> owners = lockTypes(item.getTypeId(), request.getParentId());
        SysDictType target = owners.get(request.getParentId());
        requireRevision(item.getRevision(), request.getRevision());
        ServiceAssert.isTrue(
                item.getTypeId().equals(target.getId())
                        || Integer.valueOf(1).equals(target.getStatusFlag()),
                ResultCode.PARAM_INVALID,
                "不能移入停用的字典类型");
        String previousCode = owners.get(item.getTypeId()).getTypeCode();
        ensureItemAvailable(target.getId(), request.effectiveCode(), request.effectiveValue(), id);
        applyItem(item, request);
        ServiceAssert.isTrue(
                dictItemMapper.updateById(item) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        cacheChanges.dictionaryChanged(previousCode);
        if (!previousCode.equals(target.getTypeCode())) {
            cacheChanges.dictionaryChanged(target.getTypeCode());
        }
    }

    /**
     * 修改类型或项状态；停用类型抑制业务读取，但保留子项各自状态。
     *
     * @param id 记录ID
     * @param request 该用例接收的白名单请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(String id, DictionaryStatusRequest request) {
        cacheState.lockMenuTree();
        cacheState.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        directoryPolicy.requirePlatformAdministrator();
        ServiceAssert.isTrue(
                request != null
                        && request.status() != null
                        && (request.status() == 0 || request.status() == 1),
                ResultCode.PARAM_INVALID);
        SysDictType type = getById(id);
        if (type != null) {
            type = lockType(id);
            requireRevision(type.getRevision(), request.revision());
            type.setStatusFlag(request.status());
            ServiceAssert.isTrue(updateById(type), ResultCode.DATA_CONCURRENT_CONFLICT);
        } else {
            SysDictItem item =
                    ServiceAssert.notNull(dictItemMapper.selectById(id), ResultCode.DICT_NOT_FOUND);
            type = lockType(item.getTypeId());
            requireRevision(item.getRevision(), request.revision());
            item.setStatusFlag(request.status());
            ServiceAssert.isTrue(
                    dictItemMapper.updateById(item) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        }
        cacheChanges.dictionaryChanged(type.getTypeCode());
    }

    /**
     * 禁止删除仍有活跃子项的类型；项删除校验读取时的所属类型及版本。
     *
     * @param id 记录ID
     * @param expectedRevision 客户端期望的聚合版本
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id, Integer expectedRevision) {
        cacheState.lockMenuTree();
        cacheState.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        directoryPolicy.requirePlatformAdministrator();
        SysDictType type = getById(id);
        if (type != null) {
            type = lockType(id);
            requireRevision(type.getRevision(), expectedRevision);
            ServiceAssert.isTrue(
                    dictItemMapper.selectCount(
                                    new LambdaQueryWrapper<SysDictItem>()
                                            .eq(SysDictItem::getTypeId, id))
                            == 0,
                    ResultCode.DATA_REFERENCED,
                    "请先删除字典类型下的项");
            ServiceAssert.isTrue(
                    baseMapper.update(
                                    type,
                                    new LambdaUpdateWrapper<SysDictType>()
                                            .eq(SysDictType::getId, id)
                                            .eq(SysDictType::getRevision, expectedRevision)
                                            .set(SysDictType::getDeleteFlag, 0))
                            == 1,
                    ResultCode.DATA_CONCURRENT_CONFLICT);
        } else {
            SysDictItem item =
                    ServiceAssert.notNull(dictItemMapper.selectById(id), ResultCode.DICT_NOT_FOUND);
            type = lockType(item.getTypeId());
            requireRevision(item.getRevision(), expectedRevision);
            ServiceAssert.isTrue(
                    dictItemMapper.update(
                                    item,
                                    new LambdaUpdateWrapper<SysDictItem>()
                                            .eq(SysDictItem::getId, id)
                                            .eq(SysDictItem::getTypeId, item.getTypeId())
                                            .eq(SysDictItem::getRevision, expectedRevision)
                                            .set(SysDictItem::getDeleteFlag, 0))
                            == 1,
                    ResultCode.DATA_CONCURRENT_CONFLICT);
        }
        cacheChanges.dictionaryChanged(type.getTypeCode());
    }

    private SysDictType lockType(String id) {
        return ServiceAssert.notNull(
                getOne(
                        new LambdaQueryWrapper<SysDictType>()
                                .eq(SysDictType::getId, id)
                                .last("FOR UPDATE")),
                ResultCode.DICT_NOT_FOUND);
    }

    private Map<String, SysDictType> lockTypes(String... ids) {
        Map<String, SysDictType> result = new HashMap<>();
        Arrays.stream(ids).distinct().sorted().forEach(id -> result.put(id, lockType(id)));
        return result;
    }

    private void ensureTypeCodeAvailable(String code, String exceptId) {
        ServiceAssert.isTrue(
                count(
                                new LambdaQueryWrapper<SysDictType>()
                                        .eq(SysDictType::getTypeCode, code)
                                        .ne(exceptId != null, SysDictType::getId, exceptId))
                        == 0,
                ResultCode.DICT_TYPE_DUPLICATED);
    }

    private void ensureItemAvailable(String typeId, String code, String value, String exceptId) {
        ServiceAssert.isTrue(
                dictItemMapper.selectCount(
                                new LambdaQueryWrapper<SysDictItem>()
                                        .eq(SysDictItem::getTypeId, typeId)
                                        .ne(exceptId != null, SysDictItem::getId, exceptId)
                                        .and(
                                                criteria ->
                                                        criteria.eq(SysDictItem::getItemCode, code)
                                                                .or()
                                                                .eq(
                                                                        SysDictItem::getItemValue,
                                                                        value)))
                        == 0,
                ResultCode.DATA_DUPLICATED,
                "同一类型下字典项编码和存储值不得重复");
    }

    private static void applyItem(SysDictItem item, DictionarySaveRequest request) {
        item.setTypeId(request.getParentId());
        item.setItemCode(request.effectiveCode());
        item.setItemLabel(request.effectiveLabel());
        item.setItemValue(request.effectiveValue());
        item.setRemark(request.getRemark());
        if (request.getSort() != null) {
            item.setSortNo(request.getSort());
        } else if (item.getSortNo() == null) {
            item.setSortNo(0);
        }
        if (request.getStatus() != null) {
            item.setStatusFlag(request.getStatus());
        } else if (item.getStatusFlag() == null) {
            item.setStatusFlag(1);
        }
    }

    private void validate(DictionarySaveRequest request) {
        ServiceAssert.isTrue(
                request != null && validator.validate(request).isEmpty(), ResultCode.PARAM_INVALID);
    }

    private static void requireRevision(Integer actual, Integer expected) {
        ServiceAssert.notNull(expected, ResultCode.PARAM_INVALID, "更新必须提交 revision");
        ServiceAssert.isTrue(Objects.equals(actual, expected), ResultCode.DATA_CONCURRENT_CONFLICT);
    }
}
