package com.example.library.service;

import com.example.library.domain.Policy;
import com.example.library.repo.PolicyRepository;
import com.example.library.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 规则参数服务。所有借阅/预约/罚款规则集中在此，
 * 馆员调整规则有审计入口，业务操作返回的 ruleBasis 均引用这些参数，便于向读者解释。
 */
@Service
public class PolicyService {

    private final PolicyRepository policies;

    /** 代码内兜底默认值（与 V2 种子保持一致） */
    private static final Map<String, Double> DEFAULTS = Map.ofEntries(
            Map.entry("loan.periodDays", 30.0),
            Map.entry("loan.renewLimit", 1.0),
            Map.entry("hold.pickupDays", 3.0),
            Map.entry("hold.maxPerReader", 10.0),
            Map.entry("fine.daily", 0.50),
            Map.entry("fine.cap", 20.00),
            Map.entry("fee.damage", 10.00),
            Map.entry("fee.missingPages", 20.00),
            Map.entry("fee.accessory", 5.00),
            Map.entry("fee.replacement", 50.00),
            Map.entry("fee.logisticsLoss", 60.00),
            Map.entry("transfer.sameRegionHours", 24.0),
            Map.entry("transfer.otherRegionHours", 72.0),
            Map.entry("transfer.protectionRatio", 0.5),
            Map.entry("closure.graceHours", 24.0),
            Map.entry("acquisition.waitRatio", 2.0),
            Map.entry("acquisition.minQueue", 3.0),
            Map.entry("allocation.balanceWeight", 0.5)
    );

    public PolicyService(PolicyRepository policies) {
        this.policies = policies;
    }

    @Transactional(readOnly = true)
    public double get(String key) {
        return policies.findById(key)
                .map(Policy::getNumericValue)
                .filter(v -> v != null)
                .orElseGet(() -> DEFAULTS.getOrDefault(key, 0.0));
    }

    public int loanPeriodDays()      { return (int) get("loan.periodDays"); }
    public int renewLimit()          { return (int) get("loan.renewLimit"); }
    public int pickupDays()          { return (int) get("hold.pickupDays"); }
    public int maxHoldsPerReader()   { return (int) get("hold.maxPerReader"); }
    public double fineDaily()        { return get("fine.daily"); }
    public double fineCap()          { return get("fine.cap"); }
    public double feeDamage()        { return get("fee.damage"); }
    public double feeMissingPages()  { return get("fee.missingPages"); }
    public double feeAccessory()     { return get("fee.accessory"); }
    public double feeReplacement()   { return get("fee.replacement"); }
    public double feeLogisticsLoss() { return get("fee.logisticsLoss"); }
    public double protectionRatio()  { return get("transfer.protectionRatio"); }
    public long closureGraceHours()  { return (long) get("closure.graceHours"); }

    /** 采购建议阈值：等待人数 / 在流转复本数 高于该比例时建议新增采购 */
    public double acquisitionWaitRatio() { return get("acquisition.waitRatio"); }

    /** 触发采购建议的最少等待人数 */
    public int acquisitionMinQueue() { return (int) get("acquisition.minQueue"); }

    /** 新复本分配时“分馆等待时长”相对“读者预约时间”的权重（0~1） */
    public double allocationBalanceWeight() { return get("allocation.balanceWeight"); }

    public long etaHours(boolean sameRegion) {
        return (long) get(sameRegion ? "transfer.sameRegionHours" : "transfer.otherRegionHours");
    }

    @Transactional
    public Policy update(String key, double value) {
        Policy p = policies.findById(key)
                .orElseThrow(() -> ApiException.notFound("未知规则项: " + key));
        p.setNumericValue(value);
        return p;
    }

    @Transactional(readOnly = true)
    public Map<String, Double> all() {
        java.util.HashMap<String, Double> m = new java.util.HashMap<>(DEFAULTS);
        policies.findAll().forEach(p -> {
            if (p.getNumericValue() != null) m.put(p.getKey(), p.getNumericValue());
        });
        return m;
    }
}
