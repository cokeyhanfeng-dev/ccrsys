package com.ccr.admin.mobile;

import cn.dev33.satoken.stp.StpUtil;
import com.ccr.admin.controller.AuthController;
import com.ccr.admin.system.mapper.CcrSysDeptMapper;
import com.ccr.application.controller.AttachmentController;
import com.ccr.approval.controller.ApprovalController;
import com.ccr.common.core.domain.R;
import com.ccr.common.exception.ServiceException;
import com.ccr.vote.controller.VoteController;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

/** 移动协议适配：复用现有接口内部的对象授权及事务服务，不重建审批状态机。 */
@RestController
@RequestMapping("/mobile")
@Validated
public class MobileApprovalController {
    @Resource private AuthController auth;
    @Resource private MobileAccessService access;
    @Resource private CcrSysDeptMapper departments;
    @Resource private ApprovalController approval;
    @Resource private VoteController vote;
    @Resource private AttachmentController attachments;
    @Resource private MobileApprovalCommandService commands;
    @Resource private MobileQueryService queries;

    public record Login(@NotBlank @Size(max=4096) String token) {
        @Override public String toString() { return "Login[凭证已隐藏]"; }
    }
    public record OaLogin(@NotBlank @Size(max=4096) String ticket) {
        @Override public String toString() { return "OaLogin[票据已隐藏]"; }
    }
    public record Approval(@NotNull @Positive Long applicationId, @NotBlank @Size(max=64) String nodeCode,
            @NotNull @PositiveOrZero Integer versionNo, @Size(max=500) String comment,
            @Size(max=100) Map<@NotNull Long, @NotNull @DecimalMin(value="0",inclusive=false) @DecimalMax("36") @Digits(integer=2,fraction=6) BigDecimal> rateAdjustments) {}
    public record Ballot(@NotNull @Positive Long applicationId, @NotBlank @Pattern(regexp="APPROVE|REJECT") String choice, @Size(max=500) String comment) {}
    public record Decision(@NotNull @Positive Long applicationId, @NotBlank @Pattern(regexp="APPROVE|VETO") String decision, @Size(max=500) String opinion) {}

    @PostMapping("/login") public R<?> login(@Valid @RequestBody Login body, HttpServletRequest request) {
        return auth.mobileLogin(body.token(), request);
    }
    @PostMapping("/oa/login") public R<?> oaLogin(@Valid @RequestBody OaLogin body, HttpServletRequest request) {
        return auth.mobileOaLogin(body.ticket(), request);
    }
    @GetMapping("/session") public R<?> session() {
        var user=access.requireCurrent();
        var dept=user.getOrgId()==null?null:departments.selectById(user.getOrgId());
        Map<String,Object> profile=new LinkedHashMap<>();
        profile.put("userId",user.getId());profile.put("userName",user.getUsername());profile.put("nickName",user.getNickName());
        profile.put("roles",access.requireEligible(user));profile.put("orgName",dept==null?null:dept.getDeptName());
        return R.ok(profile);
    }
    @PostMapping("/logout") public R<?> logout(HttpServletRequest request) { return auth.logout(request); }
    @GetMapping("/tasks") public R<?> tasks() {
        var roles=access.requireEligible(access.requireCurrent());
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("approval",queries.pending(approval.tasks().getData()));
        data.put("vote",roles.contains("committee_member")?vote.todo().getData():List.of());
        data.put("president",roles.contains("president")?vote.presidentTodo().getData():List.of());
        return R.ok(data);
    }
    /** 接收人强制来自登录会话，客户端不可指定或覆盖。 */
    @GetMapping("/messages") public R<?> messages() {
        return R.ok(queries.messages(StpUtil.getLoginIdAsLong()));
    }
    @GetMapping("/done") public R<?> done(@RequestParam(defaultValue="1") @Min(1) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return R.ok(queries.done(StpUtil.getLoginIdAsLong(),page,size));
    }
    @GetMapping("/applications/{id}") public R<?> detail(@PathVariable Long id) {
        var result=approval.detail(id);
        // 移动委员页仅本人票，不向普通审批人或委员提供他人实时进度、票数。
        if (!"president".equals(access.requireCurrent().getRoleCode())) {
            Object round=result.getData().get("voteRound");
            if(round instanceof Map<?,?> raw) {
                Map<String,Object> own=new LinkedHashMap<>();
                for(String key:List.of("roundId","myChoice","myComment","submitted")) if(raw.containsKey(key)) own.put(key,raw.get(key));
                result.getData().put("voteRound",own);
            }
            result.getData().remove("voteRounds");result.getData().remove("voteResults");result.getData().remove("presidentDecisions");
        }
        return result;
    }
    @PostMapping("/approve") public R<?> approve(@RequestHeader("Idempotency-Key") @NotBlank @Size(max=40) String key,@Valid @RequestBody Approval body) {
        return commands.approve(key,body);
    }
    static void validateBp(BigDecimal before,BigDecimal after) {
        if(before==null||after.subtract(before).movePointRight(2).stripTrailingZeros().scale()>0)
            throw new ServiceException(400,"请按 1 BP（0.01 个百分点）的整数倍调整利率");
    }
    @PostMapping("/reject") public R<?> reject(@RequestHeader("Idempotency-Key") @NotBlank @Size(max=40) String key,@Valid @RequestBody Approval body) {
        requireReason(body.comment());return commands.reject(key,body);
    }
    @PostMapping("/vote-rounds/{roundId}/ballots") public R<?> ballot(@PathVariable Long roundId,@RequestHeader("Idempotency-Key") @NotBlank @Size(max=40) String key,@Valid @RequestBody Ballot body) {
        if("REJECT".equals(body.choice()))requireReason(body.comment());
        return vote.submitBallot(roundId,key,Map.of("applicationId",body.applicationId(),"choice",body.choice(),"comment",Objects.toString(body.comment(),"")));
    }
    @PostMapping("/decisions") public R<?> decision(@Valid @RequestBody Decision body) {
        if("VETO".equals(body.decision()))requireReason(body.opinion());
        return vote.presidentDecision(Map.of("applicationId",body.applicationId(),"decision",body.decision(),"opinion",Objects.toString(body.opinion(),"")));
    }
    @GetMapping("/vote-rounds/{roundId}/opinions") public R<?> opinions(@PathVariable Long roundId) { return vote.roundOpinions(roundId); }
    @GetMapping("/applications/{id}/attachments/{fileId}") public ResponseEntity<byte[]> file(@PathVariable Long id,@PathVariable Long fileId) {
        var response=attachments.download(id,fileId);
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(response.getBody());
    }
    @ExceptionHandler({jakarta.validation.ConstraintViolationException.class, org.springframework.web.bind.MissingRequestHeaderException.class})
    public R<?> invalidRequest(Exception error) { return R.fail(400,"请求参数不完整或超出允许范围，请刷新后重试"); }
    private void requireReason(String reason) {if(reason==null||reason.isBlank())throw new ServiceException(400,"否决原因必填");}
}
