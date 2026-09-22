package com.ccr.application.service;

import com.ccr.application.dto.ContributionPreviewRequest;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.support.CommitmentBaselineResolver;
import com.ccr.common.core.util.RelatedCustomerResolver;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** 只读预览，当前关联人替换本申请旧关联人，沿用其他历史申请的归并口径。 */
@Service
public class ContributionPreviewService {
    @Resource private ApplicationAccessService accessService;
    @Resource private CcrApplicationMapper applicationMapper;
    @Resource private JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> preview(Long id, ContributionPreviewRequest request) {
        accessService.requireOwner(id);
        var app = applicationMapper.selectById(id);
        if (app == null) throw new ServiceException(404, "申请不存在");
        if (!"DRAFT".equals(app.getStatus())) throw new ServiceException(400, "仅草稿可预览贡献度");
        var related = CommitmentBaselineResolver.relatedCustomerNos(jdbcTemplate, app.getCustomerNo(), id);
        for (var person : request.relatedPersons()) {
            // 与保存草稿一致：空白关联人行不参与归并。
            if (person.personName() == null || person.personName().isBlank()) continue;
            String no = person.relatedCustomerNo();
            if (no == null || no.isBlank()) {
                no = RelatedCustomerResolver.resolve(jdbcTemplate, person.certType(), person.certNo());
            }
            if (no != null && !no.isBlank()) related.add(no);
        }
        return CommitmentBaselineResolver.loadContribution(jdbcTemplate, app.getCustomerNo(), app.getGroupNo(), related);
    }
}
