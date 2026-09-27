package com.github.guoyyit.fundflowriskjava.api;

import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;
import com.github.guoyyit.fundflowriskjava.service.FundFlowService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class FundFlowController {

    private final FundFlowService service;

    public FundFlowController(FundFlowService service) {
        this.service = service;
    }

    /** 批量写入转账记录，整批为一个事务；返回实际新增条数。 */
    @PostMapping("/transfers")
    public Map<String, Object> ingest(@RequestBody List<TransferRecord> records) {
        int added = service.ingest(records);
        return Map.of("added", added, "total", service.recordCount());
    }

    /** 回滚一条记录。 */
    @DeleteMapping("/transfers/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean removed = service.delete(id);
        return Map.of("removed", removed, "total", service.recordCount());
    }

    /** 按给定规则参数检测；不传 body 时使用默认参数。 */
    @PostMapping("/detect")
    public List<RiskHit> detect(@RequestBody(required = false) RuleConfig config) {
        return service.detect(config);
    }

    @GetMapping("/transfers")
    public List<TransferRecord> records() {
        return service.records();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("records", service.recordCount());
    }
}
