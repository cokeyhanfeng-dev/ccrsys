package com.ccr.admin.mobile;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import com.ccr.application.domain.CcrPricingItem;

/** 本人已办：普通审批、本人匿名票与行长决策按申请合并，避免委员已办遗漏。 */
@Service
public class MobileQueryService {
    @Resource private JdbcTemplate jdbc;
    private static final String PARTICIPATION="""
        SELECT pi.application_id, aa.operation_time handled_at FROM ccr_approval_action aa
        JOIN ccr_pricing_item pi ON pi.id=aa.pricing_item_id WHERE aa.operator_id=? AND aa.del_flag='0'
        UNION ALL SELECT pi.application_id,b.submit_time FROM ccr_ballot b
        JOIN ccr_pricing_item pi ON pi.id=b.pricing_item_id WHERE b.voter_user_hash=SHA2(?,256) AND b.del_flag='0'
        UNION ALL SELECT pi.application_id,pd.decision_time FROM ccr_president_decision pd
        JOIN ccr_pricing_item pi ON pi.id=pd.pricing_item_id WHERE pd.president_user_id=? AND pd.del_flag='0'
        """;
    public List<Map<String,Object>> pending(List<CcrPricingItem> items) {
        if(items.isEmpty()) return List.of();
        List<Long> ids=items.stream().map(CcrPricingItem::getApplicationId).distinct().toList();
        String placeholders=String.join(",", Collections.nCopies(ids.size(),"?"));
        Map<Long,Map<String,Object>> headers=new HashMap<>();
        jdbc.queryForList("SELECT id,application_no applicationNo,business_type businessType FROM ccr_application WHERE del_flag='0' AND id IN ("+placeholders+")",ids.toArray())
                .forEach(row->headers.put(((Number)row.get("id")).longValue(),row));
        List<Map<String,Object>> result=new ArrayList<>();
        for(var item:items) {
            var head=headers.get(item.getApplicationId());
            if(head==null)continue;
            Map<String,Object> row=new LinkedHashMap<>(head);
            row.put("applicationId",item.getApplicationId());row.put("id",item.getId());
            row.put("customerName",item.getCustomerName());row.put("pricingCustomerNo",item.getPricingCustomerNo());
            row.put("pricingAmount",item.getPricingAmount());row.put("pricingItemNo",item.getPricingItemNo());
            row.put("pricingCarrierType",item.getPricingCarrierType());row.put("currentNodeCode",item.getCurrentNodeCode());
            row.put("requestedRate",item.getRequestedRate());row.put("currentApprovalRate",item.getCurrentApprovalRate());
            result.add(row);
        }
        return result;
    }
    public List<Map<String,Object>> messages(Long user) {
        Objects.requireNonNull(user,"登录人不能为空");
        return jdbc.queryForList("SELECT id,message_content messageContent,create_time createTime,receipt_time receiptTime FROM ccr_notification_log WHERE recipient_id=? AND del_flag='0' AND NOT (channel='WECHAT' AND message_key LIKE 'NR:%') ORDER BY create_time DESC,id DESC LIMIT 200",user.toString());
    }
    public Map<String,Object> done(Long user,int page,int size) {
        String from=" FROM ccr_application a JOIN (SELECT application_id, MAX(handled_at) handled_at FROM ("+PARTICIPATION+") p GROUP BY application_id) h ON h.application_id=a.id WHERE a.del_flag='0'";
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,user,user,user);
        var rows=jdbc.queryForList("""
            SELECT a.id applicationId,a.application_no applicationNo,a.business_type businessType,a.status,
            COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(a.customer_info_json,'$.customerName')),''),
            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(a.group_info_json,'$.groupName')),''),a.customer_no,a.group_no) customerName,
            h.handled_at operationTime,
            (SELECT SUM(pi.pricing_amount) FROM ccr_pricing_item pi WHERE pi.application_id=a.id AND pi.del_flag='0') pricingAmount
            """+from+" ORDER BY h.handled_at DESC,a.id DESC LIMIT ? OFFSET ?",user,user,user,size,(long)(page-1)*size);
        return Map.of("rows",rows,"total",total==null?0:total);
    }
}
