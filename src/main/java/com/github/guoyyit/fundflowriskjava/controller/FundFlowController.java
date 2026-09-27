package com.github.guoyyit.fundflowriskjava.controller;

import com.github.guoyyit.fundflowriskjava.model.RiskHit;
import com.github.guoyyit.fundflowriskjava.model.RuleConfig;
import com.github.guoyyit.fundflowriskjava.model.TransferRecord;
import com.github.guoyyit.fundflowriskjava.service.FundFlowService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class FundFlowController {

    private final FundFlowService service;

    public FundFlowController(FundFlowService service) {
        this.service = service;
    }

    @PostMapping("/transfers")
    public FundFlowService.ApplyResult apply(@RequestBody List<TransferRecord> batch) {
        return service.apply(batch);
    }

    @DeleteMapping("/transfers/{txId}")
    public boolean delete(@PathVariable String txId) {
        return service.delete(txId);
    }

    @GetMapping("/risks")
    public List<RiskHit> risks(@RequestParam(required = false) Long minAmount,
                               @RequestParam(required = false) Integer maxHops,
                               @RequestParam(required = false) Long timeWindowMillis,
                               @RequestParam(required = false) Integer minCounterparties,
                               @RequestParam(required = false) Long seed) {
        RuleConfig d = RuleConfig.DEFAULT;
        RuleConfig config = new RuleConfig(
                minAmount != null ? minAmount : d.minAmount(),
                maxHops != null ? maxHops : d.maxHops(),
                timeWindowMillis != null ? timeWindowMillis : d.timeWindowMillis(),
                minCounterparties != null ? minCounterparties : d.minCounterparties(),
                seed != null ? seed : d.seed());
        return service.detect(config);
    }

    @GetMapping("/graph/stats")
    public FundFlowService.GraphStats stats() {
        return service.stats();
    }
}
