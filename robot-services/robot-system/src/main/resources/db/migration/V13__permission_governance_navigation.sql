-- DML only: publish the registered governance page without adding any API permission.
-- Stop business writes during upgrade and restart in the reviewed environment afterwards.
-- Explicit utf8mb4_bin expressions also isolate this migration from session collation.
-- Session variables and all conflict guards precede writes. A scalar UNION guard deliberately
-- raises ER_SUBQUERY_NO_1_ROW on conflict; it does not overwrite an existing route owner.

WITH RECURSIVE robot_governance_routes AS (
    SELECT m.id, m.parent_id, m.menu_type, m.component, m.status_flag, m.visible_flag,
           m.perm_code,
           CAST(REGEXP_REPLACE(CONCAT(_utf8mb4'/' COLLATE utf8mb4_bin,
               COALESCE(m.path COLLATE utf8mb4_bin, _utf8mb4'' COLLATE utf8mb4_bin))
               COLLATE utf8mb4_bin, _utf8mb4'/+' COLLATE utf8mb4_bin,
               _utf8mb4'/' COLLATE utf8mb4_bin) AS CHAR(16384) CHARACTER SET utf8mb4)
               COLLATE utf8mb4_bin AS full_path,
           CAST(CONCAT(_utf8mb4'|' COLLATE utf8mb4_bin, m.id COLLATE utf8mb4_bin,
               _utf8mb4'|' COLLATE utf8mb4_bin) AS CHAR(8192) CHARACTER SET utf8mb4)
               COLLATE utf8mb4_bin AS visited_ids, 1 AS depth
    FROM sys_menu m
    WHERE m.delete_flag = 1
      AND (m.path COLLATE utf8mb4_bin LIKE _utf8mb4'/%' COLLATE utf8mb4_bin
           OR m.parent_id IS NULL
           OR m.parent_id COLLATE utf8mb4_bin = _utf8mb4'' COLLATE utf8mb4_bin)
    UNION ALL
    SELECT m.id, m.parent_id, m.menu_type, m.component, m.status_flag, m.visible_flag,
           m.perm_code,
           REGEXP_REPLACE(CONCAT(p.full_path COLLATE utf8mb4_bin,
               _utf8mb4'/' COLLATE utf8mb4_bin, COALESCE(m.path COLLATE utf8mb4_bin,
               _utf8mb4'' COLLATE utf8mb4_bin)) COLLATE utf8mb4_bin,
               _utf8mb4'/+' COLLATE utf8mb4_bin, _utf8mb4'/' COLLATE utf8mb4_bin),
           CONCAT(p.visited_ids COLLATE utf8mb4_bin, m.id COLLATE utf8mb4_bin,
               _utf8mb4'|' COLLATE utf8mb4_bin) COLLATE utf8mb4_bin, p.depth + 1
    FROM sys_menu m JOIN robot_governance_routes p
      ON m.parent_id COLLATE utf8mb4_bin = p.id COLLATE utf8mb4_bin
    WHERE m.delete_flag = 1
      AND m.path COLLATE utf8mb4_bin NOT LIKE _utf8mb4'/%' COLLATE utf8mb4_bin
      AND p.depth < 64
      AND LOCATE(CONCAT(_utf8mb4'|' COLLATE utf8mb4_bin, m.id COLLATE utf8mb4_bin,
              _utf8mb4'|' COLLATE utf8mb4_bin) COLLATE utf8mb4_bin,
              p.visited_ids COLLATE utf8mb4_bin) = 0
)
SELECT COUNT(*),
       COALESCE(SUM(LOWER(TRIM(TRAILING _utf8mb4'/' COLLATE utf8mb4_bin
           FROM full_path COLLATE utf8mb4_bin)) COLLATE utf8mb4_bin
           = _utf8mb4'/sys-manage/permission-manage' COLLATE utf8mb4_bin), 0),
       MIN(CASE WHEN LOWER(TRIM(TRAILING _utf8mb4'/' COLLATE utf8mb4_bin
           FROM full_path COLLATE utf8mb4_bin)) COLLATE utf8mb4_bin
           = _utf8mb4'/sys-manage/permission-manage' COLLATE utf8mb4_bin THEN id COLLATE utf8mb4_bin END),
       COALESCE(SUM(LOWER(TRIM(TRAILING _utf8mb4'/' COLLATE utf8mb4_bin
           FROM full_path COLLATE utf8mb4_bin)) COLLATE utf8mb4_bin
           = _utf8mb4'/sys-manage/permission-manage' COLLATE utf8mb4_bin
           AND parent_id COLLATE utf8mb4_bin = _utf8mb4'menu-sys' COLLATE utf8mb4_bin
           AND menu_type COLLATE utf8mb4_bin = _utf8mb4'menu' COLLATE utf8mb4_bin
           AND TRIM(LEADING _utf8mb4'/' COLLATE utf8mb4_bin
               FROM component COLLATE utf8mb4_bin) COLLATE utf8mb4_bin
               = _utf8mb4'sys-manage/permission-manage/index' COLLATE utf8mb4_bin
           AND status_flag = 1 AND visible_flag = 1
           AND (perm_code IS NULL
               OR TRIM(perm_code COLLATE utf8mb4_bin) = _utf8mb4'' COLLATE utf8mb4_bin
               OR perm_code COLLATE utf8mb4_bin = _utf8mb4'sys:role:list' COLLATE utf8mb4_bin)), 0)
INTO @robot_governance_resolved_count, @robot_governance_owner_count,
     @robot_governance_owner_id, @robot_governance_valid_owner_count
FROM robot_governance_routes;

SET @robot_governance_owner_id = COALESCE(@robot_governance_owner_id COLLATE utf8mb4_bin,
    _utf8mb4'menu-sys-permission' COLLATE utf8mb4_bin);
SET @robot_governance_route_guard = (
    SELECT 1 UNION ALL SELECT 1
    WHERE @robot_governance_resolved_count <> (SELECT COUNT(*) FROM sys_menu WHERE delete_flag = 1)
       OR @robot_governance_owner_count > 1
       OR (@robot_governance_owner_count = 1 AND @robot_governance_valid_owner_count <> 1)
       OR NOT EXISTS (SELECT 1 FROM sys_menu
           WHERE id COLLATE utf8mb4_bin = _utf8mb4'menu-sys' COLLATE utf8mb4_bin
           AND menu_type COLLATE utf8mb4_bin = _utf8mb4'directory' COLLATE utf8mb4_bin
           AND parent_id IS NULL
           AND LOWER(TRIM(TRAILING _utf8mb4'/' COLLATE utf8mb4_bin
               FROM REGEXP_REPLACE(path COLLATE utf8mb4_bin,
                   _utf8mb4'/+' COLLATE utf8mb4_bin,
                   _utf8mb4'/' COLLATE utf8mb4_bin))) COLLATE utf8mb4_bin
               = _utf8mb4'/sys-manage' COLLATE utf8mb4_bin AND delete_flag = 1 AND status_flag = 1)
       OR EXISTS (SELECT 1 FROM sys_menu
           WHERE id COLLATE utf8mb4_bin = _utf8mb4'menu-sys-permission' COLLATE utf8mb4_bin
           AND (@robot_governance_owner_count = 0 OR id COLLATE utf8mb4_bin
               <> @robot_governance_owner_id COLLATE utf8mb4_bin))
       OR (SELECT COUNT(*) FROM sys_cache_epoch WHERE scope COLLATE utf8mb4_bin
           IN (_utf8mb4'permissions' COLLATE utf8mb4_bin, _utf8mb4'menu-tree' COLLATE utf8mb4_bin)) <> 2
);

-- Deterministic binding IDs must not replace even a previously deleted relation.
SET @robot_governance_binding_guard = (
    SELECT 1 UNION ALL SELECT 1
    WHERE EXISTS (
        SELECT 1 FROM sys_role r JOIN sys_role_menu occupied
          ON occupied.id COLLATE utf8mb4_bin =
             CAST(SHA2(CONCAT(_utf8mb4'V13-governance:' COLLATE utf8mb4_bin,
                 r.id COLLATE utf8mb4_bin, _utf8mb4':' COLLATE utf8mb4_bin,
                 @robot_governance_owner_id COLLATE utf8mb4_bin) COLLATE utf8mb4_bin, 256)
                 AS CHAR(64) CHARACTER SET utf8mb4) COLLATE utf8mb4_bin
        WHERE r.delete_flag = 1 AND r.status_flag = 1
          AND EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m
              ON m.id COLLATE utf8mb4_bin = rm.menu_id COLLATE utf8mb4_bin
              WHERE rm.role_id COLLATE utf8mb4_bin = r.id COLLATE utf8mb4_bin
                AND rm.delete_flag = 1 AND m.delete_flag = 1 AND m.status_flag = 1
                AND m.menu_type COLLATE utf8mb4_bin = _utf8mb4'menu' COLLATE utf8mb4_bin
                AND m.perm_code COLLATE utf8mb4_bin = _utf8mb4'sys:role:list' COLLATE utf8mb4_bin)
          AND NOT EXISTS (SELECT 1 FROM sys_role_menu bound
              WHERE bound.role_id COLLATE utf8mb4_bin = r.id COLLATE utf8mb4_bin
              AND bound.menu_id COLLATE utf8mb4_bin
                  = @robot_governance_owner_id COLLATE utf8mb4_bin AND bound.delete_flag = 1)
    )
);

INSERT INTO sys_menu (id, parent_id, menu_type, menu_name, path, component, icon, sort_no,
    visible_flag, keep_alive_flag, status_flag, perm_code, create_user_no, create_date_time,
    delete_flag, revision)
SELECT _utf8mb4'menu-sys-permission' COLLATE utf8mb4_bin,
       _utf8mb4'menu-sys' COLLATE utf8mb4_bin, _utf8mb4'menu' COLLATE utf8mb4_bin,
       _utf8mb4'权限治理' COLLATE utf8mb4_bin,
       _utf8mb4'/sys-manage/permission-manage' COLLATE utf8mb4_bin,
       _utf8mb4'sys-manage/permission-manage/index' COLLATE utf8mb4_bin,
       NULL, 4, 1, 0, 1, NULL, _utf8mb4'MIGRATION' COLLATE utf8mb4_bin, UTC_TIMESTAMP(), 1, 0
FROM DUAL WHERE @robot_governance_owner_count = 0;

INSERT INTO sys_role_menu (id, role_id, menu_id, create_user_no, create_date_time, delete_flag, revision)
SELECT CAST(SHA2(CONCAT(_utf8mb4'V13-governance:' COLLATE utf8mb4_bin,
           r.id COLLATE utf8mb4_bin, _utf8mb4':' COLLATE utf8mb4_bin,
           @robot_governance_owner_id COLLATE utf8mb4_bin) COLLATE utf8mb4_bin, 256)
           AS CHAR(64) CHARACTER SET utf8mb4) COLLATE utf8mb4_bin,
       r.id, @robot_governance_owner_id COLLATE utf8mb4_bin,
       _utf8mb4'MIGRATION' COLLATE utf8mb4_bin, UTC_TIMESTAMP(), 1, 0
FROM sys_role r
WHERE r.delete_flag = 1 AND r.status_flag = 1
  AND EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m
      ON m.id COLLATE utf8mb4_bin = rm.menu_id COLLATE utf8mb4_bin
      WHERE rm.role_id COLLATE utf8mb4_bin = r.id COLLATE utf8mb4_bin
        AND rm.delete_flag = 1 AND m.delete_flag = 1 AND m.status_flag = 1
        AND m.menu_type COLLATE utf8mb4_bin = _utf8mb4'menu' COLLATE utf8mb4_bin
        AND m.perm_code COLLATE utf8mb4_bin = _utf8mb4'sys:role:list' COLLATE utf8mb4_bin)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu bound
      WHERE bound.role_id COLLATE utf8mb4_bin = r.id COLLATE utf8mb4_bin
        AND bound.menu_id COLLATE utf8mb4_bin
            = @robot_governance_owner_id COLLATE utf8mb4_bin AND bound.delete_flag = 1);

-- Navigation cache invalidation only; no fabricated business audit or environment-dependent key.
UPDATE sys_cache_epoch SET version = version + 1
WHERE scope COLLATE utf8mb4_bin
    IN (_utf8mb4'permissions' COLLATE utf8mb4_bin, _utf8mb4'menu-tree' COLLATE utf8mb4_bin);
