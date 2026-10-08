package com.robot.system.dict.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.redis.core.RedisHelper;
import com.robot.system.support.SystemKeys;
import com.robot.system.api.entity.SysDictItem;
import com.robot.system.api.entity.SysDictType;
import com.robot.system.dict.mapper.SysDictItemMapper;
import com.robot.system.dict.mapper.SysDictTypeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import com.robot.system.api.vo.dict.DictNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 字典服务：类型 + 项两级树；读取走 Redis 缓存（30 分钟 TTL + 抖动），
 * 写操作后删缓存（延迟双删的删缓存半程，读侧回填防穿透）。
 */
@Service
@RequiredArgsConstructor
public class DictService extends ServiceImpl<SysDictTypeMapper, SysDictType> {

    private static final Duration DICT_TTL = Duration.ofMinutes(30);

    private final SysDictItemMapper dictItemMapper;
    private final RedisHelper redisHelper;

    public List<DictNode> dictTree() {
        List<SysDictType> types = list(new LambdaQueryWrapper<SysDictType>()
                .eq(SysDictType::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .orderByAsc(SysDictType::getTypeCode));
        List<SysDictItem> items = dictItemMapper.selectList(new LambdaQueryWrapper<SysDictItem>()
                .eq(SysDictItem::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .orderByAsc(SysDictItem::getSortNo));

        return types.stream()
                .map(type -> DictNode.ofType(type.getId(), type.getTypeCode(),
                        type.getTypeName(), type.getRemark(),
                        items.stream()
                                .filter(item -> type.getId().equals(item.getTypeId()))
                                .map(item -> DictNode.ofItem(item.getId(), type.getId(),
                                        item.getItemLabel(), item.getItemValue(),
                                        item.getSortNo(), item.getStatusFlag()))
                                .toList()))
                .toList();
    }

    /** 按类型编码取有效项（缓存优先，防穿透空值占位）。 */
    public List<Map<String, String>> itemsByCode(String typeCode) {
        Map<?, ?> cached = redisHelper.getOrLoad(SystemKeys.dictCache(typeCode),
                Map.class, DICT_TTL,
                () -> loadItemsAsMap(typeCode));
        if (cached == null) {
            return List.of();
        }
        return convertItems(cached);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadItemsAsMap(String typeCode) {
        SysDictType type = getOne(new LambdaQueryWrapper<SysDictType>()
                .eq(SysDictType::getTypeCode, typeCode)
                .eq(SysDictType::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .last("LIMIT 1"));
        if (type == null) {
            return null;
        }
        Map<String, Object> payload = new HashMap<>(4);
        List<Map<String, String>> loaded = new ArrayList<>();
        for (SysDictItem item : dictItemMapper.selectList(
                new LambdaQueryWrapper<SysDictItem>()
                        .eq(SysDictItem::getTypeId, type.getId())
                        .eq(SysDictItem::getStatusFlag, 1)
                        .eq(SysDictItem::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                        .orderByAsc(SysDictItem::getSortNo))) {
            Map<String, String> entry = new HashMap<>(4);
            entry.put("label", item.getItemLabel());
            entry.put("value", item.getItemValue());
            loaded.add(entry);
        }
        payload.put("items", loaded);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> convertItems(Map<?, ?> cached) {
        Object items = cached.get("items");
        if (!(items instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, String>> result = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof Map<?, ?> raw) {
                Map<String, String> entry = new HashMap<>(4);
                raw.forEach((key, value) -> {
                    if (value != null) {
                        entry.put(String.valueOf(key), String.valueOf(value));
                    }
                });
                result.add(entry);
            }
        }
        return result;
    }

    /** 新增字典（type/item 双形态，解析收敛在服务内；null 字段按缺失处理）。 */
    @Transactional(rollbackFor = Exception.class)
    public String create(Map<String, Object> body) {
        if ("item".equals(String.valueOf(body.getOrDefault("type", "type")))) {
            return createItem(textOf(body, "parentId"), textOf(body, "label"),
                    textOf(body, "value"), intOf(body, "sort"));
        }
        return createType(textOf(body, "code"), textOf(body, "name"), textOf(body, "remark"));
    }

    @Transactional(rollbackFor = Exception.class)
    public String createType(String typeCode, String typeName, String remark) {
        ServiceAssert.isTrue(count(new LambdaQueryWrapper<SysDictType>()
                .eq(SysDictType::getTypeCode, typeCode)
                .eq(SysDictType::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)) == 0,
                ResultCode.DICT_TYPE_DUPLICATED);
        SysDictType type = new SysDictType();
        type.setTypeCode(typeCode);
        type.setTypeName(typeName);
        type.setRemark(remark);
        save(type);
        return type.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public String createItem(String typeId, String label, String value, Integer sortNo) {
        SysDictType type = ServiceAssert.notNull(getById(typeId), ResultCode.DICT_NOT_FOUND);
        ServiceAssert.notNull(type, ResultCode.DICT_NOT_FOUND);
        SysDictItem item = new SysDictItem();
        item.setTypeId(typeId);
        item.setItemLabel(label);
        item.setItemValue(value);
        item.setSortNo(sortNo == null ? 0 : sortNo);
        item.setStatusFlag(1);
        dictItemMapper.insert(item);
        redisHelper.evict(SystemKeys.dictCache(type.getTypeCode()));
        return item.getId();
    }

    /** 更新字典（类型或项，前端 PUT /sys/dictionaries/{id}；null 字段跳过不覆盖）。 */
    @Transactional(rollbackFor = Exception.class)
    public void update(String id, Map<String, Object> body) {
        SysDictType type = getById(id);
        if (type != null) {
            String name = textOf(body, "name");
            if (name != null) {
                type.setTypeName(name);
            }
            type.setRemark(textOf(body, "remark"));
            updateById(type);
            redisHelper.evict(SystemKeys.dictCache(type.getTypeCode()));
            return;
        }
        SysDictItem item = ServiceAssert.notNull(dictItemMapper.selectById(id),
                ResultCode.DICT_NOT_FOUND);
        String label = textOf(body, "label");
        if (label != null) {
            item.setItemLabel(label);
        }
        String value = textOf(body, "value");
        if (value != null) {
            item.setItemValue(value);
        }
        Integer sort = intOf(body, "sort");
        if (sort != null) {
            item.setSortNo(sort);
        }
        dictItemMapper.updateById(item);
        SysDictType owner = getById(item.getTypeId());
        if (owner != null) {
            redisHelper.evict(SystemKeys.dictCache(owner.getTypeCode()));
        }
    }

    /** null 安全取文本：缺失或 null 返回 null（调用方判定是否覆盖）。 */
    private static String textOf(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 宽容取整数：缺失/非法返回 null（不因脏数据 500）。 */
    private static Integer intOf(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 启用/停用字典项（前端 PUT /sys/dictionaries/{id}/status）。 */
    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(String id, Integer status) {
        ServiceAssert.isTrue(status != null && (status == 0 || status == 1),
                ResultCode.PARAM_INVALID, "状态值只允许 0/1");
        SysDictItem item = ServiceAssert.notNull(dictItemMapper.selectById(id),
                ResultCode.DICT_NOT_FOUND);
        item.setStatusFlag(status);
        dictItemMapper.updateById(item);
        SysDictType owner = getById(item.getTypeId());
        if (owner != null) {
            redisHelper.evict(SystemKeys.dictCache(owner.getTypeCode()));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        SysDictType type = getById(id);
        if (type != null) {
            // MP 逻辑删除（软删）
            removeById(id);
            redisHelper.evict(SystemKeys.dictCache(type.getTypeCode()));
            return;
        }
        SysDictItem item = dictItemMapper.selectById(id);
        ServiceAssert.notNull(item, ResultCode.DICT_NOT_FOUND);
        dictItemMapper.deleteById(id);
        SysDictType owner = getById(item.getTypeId());
        if (owner != null) {
            redisHelper.evict(SystemKeys.dictCache(owner.getTypeCode()));
        }
    }
}
