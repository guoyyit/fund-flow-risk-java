package com.github.guoyyit.fundflowriskjava.config;

import com.github.guoyyit.fundflowriskjava.detect.AddressReuseRule;
import com.github.guoyyit.fundflowriskjava.detect.CycleBackflowRule;
import com.github.guoyyit.fundflowriskjava.detect.DetectionEngine;
import com.github.guoyyit.fundflowriskjava.detect.LayeredSplitRule;
import com.github.guoyyit.fundflowriskjava.detect.RapidAggregationRule;
import com.github.guoyyit.fundflowriskjava.detect.RiskRule;
import com.github.guoyyit.fundflowriskjava.persistence.WalStore;
import com.github.guoyyit.fundflowriskjava.service.FundFlowService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

@Configuration
public class WiringConfig {

    @Bean(destroyMethod = "close")
    public WalStore walStore(@Value("${fundflow.wal-file:data/fundflow.wal}") String walFile) {
        return new WalStore(Path.of(walFile));
    }

    @Bean
    public DetectionEngine detectionEngine() {
        List<RiskRule> rules = List.of(
                new CycleBackflowRule(),
                new LayeredSplitRule(),
                new RapidAggregationRule(),
                new AddressReuseRule());
        return new DetectionEngine(rules);
    }

    @Bean
    public FundFlowService fundFlowService(WalStore walStore, DetectionEngine engine) {
        FundFlowService service = new FundFlowService(walStore, engine);
        service.recover();
        return service;
    }
}
