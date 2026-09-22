package com.ccr.application;

import com.ccr.application.domain.CcrApplication;
import com.ccr.application.dto.ContributionPreviewRequest;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.service.ApplicationAccessService;
import com.ccr.application.service.ContributionPreviewService;
import com.ccr.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContributionPreviewServiceTest {
    private final ApplicationAccessService access = mock(ApplicationAccessService.class);
    private final CcrApplicationMapper mapper = mock(CcrApplicationMapper.class);
    private final CommitmentBaselineConsistencyTest.Warehouse jdbc = spy(new CommitmentBaselineConsistencyTest.Warehouse());
    private final ContributionPreviewService service = new ContributionPreviewService();

    ContributionPreviewServiceTest() {
        ReflectionTestUtils.setField(service, "accessService", access);
        ReflectionTestUtils.setField(service, "applicationMapper", mapper);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
        CcrApplication app = new CcrApplication();
        app.setId(1L); app.setStatus("DRAFT"); app.setCustomerNo("FAKE_MAIN");
        when(mapper.selectById(1L)).thenReturn(app);
        jdbc.relatedSaved = false;
    }
    private ContributionPreviewRequest request(boolean add) {
        return new ContributionPreviewRequest(add ? List.of(new ContributionPreviewRequest.RelatedPerson(
                "模拟关联人", null, null, "FAKE_RELATED")) : List.of());
    }
    private BigDecimal value(List<Map<String, Object>> rows) {
        return (BigDecimal) rows.stream().filter(row -> CommitmentBaselineConsistencyTest.PAYROLL.equals(row.get("metricCode")))
                .findFirst().orElseThrow().get("metricValue");
    }
    @Test void pendingAdditionRemovalAndRepeatedPreviewDoNotWriteOrAccumulate() {
        assertEquals(new BigDecimal("253"), value(service.preview(1L, request(true))));
        assertEquals(new BigDecimal("253"), value(service.preview(1L, request(true))));
        assertEquals(BigDecimal.ZERO, value(service.preview(1L, request(false))));
        verify(mapper, never()).updateById(any(CcrApplication.class));
        verify(jdbc, atLeastOnce()).queryForList(contains("AND a.id <> ?"), eq("FAKE_MAIN"), eq(1L));
    }
    @Test void deniesOtherOwnerBeforeReadingContribution() {
        doThrow(new ServiceException(403, "无权维护该申请")).when(access).requireOwner(1L);
        assertThrows(ServiceException.class, () -> service.preview(1L, request(true)));
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsSubmittedApplication() {
        CcrApplication app = new CcrApplication(); app.setStatus("SUBMITTED");
        when(mapper.selectById(1L)).thenReturn(app);
        assertThrows(ServiceException.class, () -> service.preview(1L, request(true)));
        verifyNoInteractions(jdbc);
    }
    @Test void ignoresBlankRowsLikeDraftSave() {
        var request = new ContributionPreviewRequest(List.of(
                new ContributionPreviewRequest.RelatedPerson(" ", null, null, "FAKE_RELATED")));
        assertEquals(BigDecimal.ZERO, value(service.preview(1L, request)));
    }
    @Test void validatesMissingOversizedAndInvalidRelatedRows() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(new ContributionPreviewRequest(null)).isEmpty());
            assertTrue(validator.validate(request(false)).isEmpty());
            var row = new ContributionPreviewRequest.RelatedPerson("模拟关联人", null, null, "FAKE_RELATED");
            assertFalse(validator.validate(new ContributionPreviewRequest(java.util.Collections.nCopies(201, row))).isEmpty());
            assertFalse(validator.validate(new ContributionPreviewRequest(List.of(
                    new ContributionPreviewRequest.RelatedPerson("模拟关联人", null, null, "X".repeat(65))))).isEmpty());
        }
    }

}
