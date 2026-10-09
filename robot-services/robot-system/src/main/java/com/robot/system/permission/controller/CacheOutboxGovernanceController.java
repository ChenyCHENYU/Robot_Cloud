package com.robot.system.permission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.robot.common.core.domain.Result;
import com.robot.system.api.dto.cache.CacheOutboxPreviewRequest;
import com.robot.system.api.dto.cache.CacheOutboxReplayRequest;
import com.robot.system.api.vo.cache.CacheOutboxEventView;
import com.robot.system.api.vo.cache.CacheOutboxReplayPreview;
import com.robot.system.api.vo.cache.CacheOutboxReplayResult;
import com.robot.system.permission.service.CacheOutboxGovernanceService;
import com.robot.system.permission.service.PermissionStateStore;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 运维治理入口；专属权限检查与服务内永久角色事实共同约束全部操作。 */
@Tag(name = "缓存通知治理")
@RestController
@RequestMapping("/sys/cache-outbox")
@RequiredArgsConstructor
@SaCheckPermission("sys:cache-outbox:manage")
public class CacheOutboxGovernanceController {
    private final CacheOutboxGovernanceService service;

    /** 查看已提交控制账本的积压。 */
    @Operation(summary = "查询缓存通知积压")
    @GetMapping("/summary")
    public Result<PermissionStateStore.OutboxSnapshot> summary() {
        return Result.success(service.summary());
    }

    /** 查询最近事件，最多一百条。 */
    @Operation(summary = "查询最近缓存通知")
    @GetMapping("/events")
    public Result<List<CacheOutboxEventView>> events(@RequestParam(defaultValue = "50") int limit) {
        return Result.success(service.recent(limit));
    }

    /** 核对指定事件的只追加重放审计。 */
    @Operation(summary = "查询指定通知的重放审计")
    @GetMapping("/{eventId}/replays")
    public Result<List<PermissionStateStore.ReplayAudit>> replayHistory(@PathVariable String eventId) {
        return Result.success(service.replayHistory(eventId));
    }

    /** 预览不会投递或改变状态，确认时必须提交本次 planHash。 */
    @Operation(summary = "预览缓存通知重放")
    @PostMapping("/{eventId}/replay-preview")
    public Result<CacheOutboxReplayPreview> preview(@PathVariable String eventId,
                                                   @Valid @RequestBody CacheOutboxPreviewRequest request) {
        return Result.success(service.preview(eventId, request.reason()));
    }

    /** 确认重新排队；成功仅表示排队和审计已提交。 */
    @Operation(summary = "确认缓存通知重新排队")
    @PostMapping("/{eventId}/replay")
    public Result<CacheOutboxReplayResult> replay(@PathVariable String eventId,
                                                 @Valid @RequestBody CacheOutboxReplayRequest request) {
        return Result.success(service.replay(eventId, request.reason(), request.planHash()));
    }
}
