package com.github.guoyyit.fundflowriskjava.config;

import com.github.guoyyit.fundflowriskjava.persistence.WalStore;
import com.github.guoyyit.fundflowriskjava.service.FundFlowService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class ServiceConfiguration {

    @Bean
    public WalStore walStore(@Value("${fundflow.wal.path:data/fundflow.wal}") String walPath) {
        return new WalStore(Path.of(walPath));
    }

    @Bean
    public FundFlowService fundFlowService(WalStore walStore) {
        return new FundFlowService(walStore);
    }
}
