package com.robot.system.api.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.BaseEntity;
import com.robot.common.mybatis.domain.TenantEntity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * 实体 ↔ DDL 结构对齐守护（框架级防漂移）：
 *
 * <ul>
 *   <li>每个实体类必须有 @TableName 且表在 Flyway DDL 中存在；
 *   <li>实体映射列（含继承的治理字段）必须全部存在于建表语句；
 *   <li>TenantEntity 子类的表必须含 company_id；BaseEntity 子类的表必须不含 company_id（否则审计填充会向不存在的列写入导致 SQL 失败）。
 * </ul>
 *
 * 该测试可在 CI 阶段拦截「实体字段/基类选型/DDL」三方漂移。
 */
class EntityDdlAlignmentTest {
    private static final int MINIMUM_ENTITY_COUNT = 14;
    private static final int TENANT_ENTITY_COUNT = 8;
    private static final int GLOBAL_ENTITY_COUNT = 9;

    private static final String ENTITY_PACKAGE = "com.robot.system.api.entity";

    private static final Pattern CREATE_TABLE =
            Pattern.compile(
                    "CREATE\\s+TABLE\\s+`?(\\w+)`?\\s*\\((.*?)\\)\\s*ENGINE",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern COLUMN_LINE =
            Pattern.compile(
                    "^\\s*`?(\\w+)`?\\s+(?:VARCHAR|TINYINT|INT|BIGINT|DATETIME|DECIMAL|TEXT|CHAR|DATE)",
                    Pattern.CASE_INSENSITIVE);

    private static final Set<String> NON_COLUMN_PREFIXES =
            Set.of("PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN");

    private static final Pattern ALTER_ADD_COLUMN =
            Pattern.compile(
                    "ALTER\\s+TABLE\\s+`?(\\w+)`?\\s+ADD\\s+COLUMN\\s+`?(\\w+)`?\\s+",
                    Pattern.CASE_INSENSITIVE);

    private static Map<String, Set<String>> ddlColumns;

    @BeforeAll
    static void loadDdl() throws IOException {
        ddlColumns = new HashMap<>();
        for (var file :
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:db/migration/V*__*.sql")) {
            parseDdl(readSql(file));
        }
        assertFalse(ddlColumns.isEmpty(), "DDL 解析结果为空，测试自身失效");
    }

    private static void parseDdl(String sql) {
        Matcher matcher = CREATE_TABLE.matcher(sql);
        while (matcher.find()) {
            Set<String> columns = columnsOf(matcher.group(1));
            columns.addAll(createdColumns(matcher.group(2)));
        }
        Matcher additions = ALTER_ADD_COLUMN.matcher(sql);
        while (additions.find()) {
            columnsOf(additions.group(1))
                    .add(additions.group(2).toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static String readSql(org.springframework.core.io.Resource file) throws IOException {
        try (var stream = file.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> createdColumns(String body) {
        Set<String> columns = new TreeSet<>();
        for (String line : body.split("\n")) {
            Matcher columnMatcher = COLUMN_LINE.matcher(line);
            if (columnMatcher.find()) {
                String token = columnMatcher.group(1);
                if (!NON_COLUMN_PREFIXES.contains(token.toUpperCase(java.util.Locale.ROOT))) {
                    columns.add(token.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return columns;
    }

    private static Set<String> columnsOf(String name) {
        return ddlColumns.computeIfAbsent(
                name.toLowerCase(java.util.Locale.ROOT), key -> new TreeSet<>());
    }

    private static List<Class<?>> entityClasses() {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        // 只扫描标注 @TableName 的具体类（排除测试类等非实体）
        provider.addIncludeFilter(
                (MetadataReader reader, MetadataReaderFactory factory) ->
                        reader.getClassMetadata().isConcrete()
                                && reader.getAnnotationMetadata()
                                        .hasAnnotation(TableName.class.getName()));
        return provider.findCandidateComponents(ENTITY_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(
                        name -> {
                            try {
                                return Class.forName(
                                        name,
                                        false,
                                        Thread.currentThread().getContextClassLoader());
                            } catch (ClassNotFoundException e) {
                                throw new IllegalStateException(
                                        "Cannot load scanned entity: " + name, e);
                            }
                        })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /** 实体映射列：字段名驼峰转下划线，@TableField 显式值优先，@TableId 记 id。 */
    private static Set<String> mappedColumns(Class<?> entityClass) {
        Set<String> columns = new TreeSet<>();
        for (Class<?> cls = entityClass;
                cls != null && cls != Object.class;
                cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                TableId tableId = field.getAnnotation(TableId.class);
                if (tableId != null) {
                    columns.add("id");
                    continue;
                }
                TableField tableField = field.getAnnotation(TableField.class);
                if (tableField != null && !tableField.value().isEmpty()) {
                    columns.add(tableField.value().toLowerCase(java.util.Locale.ROOT));
                    continue;
                }
                columns.add(camelToSnake(field.getName()));
            }
        }
        return columns;
    }

    private static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    @DisplayName("实体 ↔ DDL 全量对齐：映射列存在 + 租户基类选型正确")
    void entitiesShouldAlignWithDdl() {
        List<Class<?>> entities = entityClasses();
        assertTrue(entities.size() >= MINIMUM_ENTITY_COUNT, "实体扫描数量异常: " + entities.size());

        List<String> violations = new ArrayList<>();
        for (Class<?> entityClass : entities) {
            verifyEntity(entityClass, violations);
        }
        assertTrue(violations.isEmpty(), "实体-DDL 漂移:\n  " + String.join("\n  ", violations));
    }

    private static void verifyEntity(Class<?> entityClass, List<String> violations) {
        TableName tableName = entityClass.getAnnotation(TableName.class);
        assertNotNull(tableName, entityClass.getSimpleName() + " 缺少 @TableName");
        Set<String> ddl = ddlColumns.get(tableName.value().toLowerCase(java.util.Locale.ROOT));
        if (ddl == null) {
            violations.add(
                    entityClass.getSimpleName() + " -> 表 " + tableName.value() + " 不在 DDL 中");
            return;
        }
        verifyMappedColumns(entityClass, tableName, ddl, violations);
        verifyTenantBase(entityClass, ddl, violations);
        verifyGovernanceColumns(entityClass, tableName, ddl, violations);
    }

    private static void verifyMappedColumns(
            Class<?> entityClass, TableName tableName, Set<String> ddl, List<String> violations) {
        // 1. 实体映射列必须全部存在于建表语句
        for (String column : mappedColumns(entityClass)) {
            if (!ddl.contains(column)) {
                violations.add(
                        entityClass.getSimpleName()
                                + "."
                                + column
                                + " 在表 "
                                + tableName.value()
                                + " 的 DDL 中不存在");
            }
        }
    }

    private static void verifyTenantBase(
            Class<?> entityClass, Set<String> ddl, List<String> violations) {
        // 2. 租户基类选型必须与表结构一致（双向断言）
        boolean tenantEntity = TenantEntity.class.isAssignableFrom(entityClass);
        boolean hasCompanyColumn = ddl.contains("company_id");
        if (tenantEntity && !hasCompanyColumn) {
            violations.add(entityClass.getSimpleName() + " 继承 TenantEntity 但表缺少 company_id 列");
        }
        if (!tenantEntity && hasCompanyColumn) {
            violations.add(
                    entityClass.getSimpleName()
                            + " 仅继承 BaseEntity 但表存在 company_id 列（审计填充将写入失败，应继承 TenantEntity）");
        }
    }

    private static void verifyGovernanceColumns(
            Class<?> entityClass, TableName tableName, Set<String> ddl, List<String> violations) {
        // 3. 治理字段基线必须齐备
        for (String base :
                Arrays.asList(
                        "id",
                        "create_user_no",
                        "create_date_time",
                        "update_user_no",
                        "update_date_time",
                        "delete_flag",
                        "revision")) {
            if (!ddl.contains(base)) {
                violations.add(
                        entityClass.getSimpleName()
                                + " 表 "
                                + tableName.value()
                                + " 缺少治理基线列 "
                                + base);
            }
        }
    }

    @Test
    @DisplayName("租户/全局表分布符合架构声明")
    void tenantTableDistributionShouldMatchDesign() {
        long tenantEntities =
                entityClasses().stream().filter(TenantEntity.class::isAssignableFrom).count();
        long globalEntities =
                entityClasses().stream()
                        .filter(
                                c ->
                                        BaseEntity.class.isAssignableFrom(c)
                                                && !TenantEntity.class.isAssignableFrom(c))
                        .count();
        assertEquals(TENANT_ENTITY_COUNT, tenantEntities, "租户表实体数量变化需同步更新架构文档");
        assertEquals(GLOBAL_ENTITY_COUNT, globalEntities, "全局表实体数量变化需同步更新架构文档");
    }
}
