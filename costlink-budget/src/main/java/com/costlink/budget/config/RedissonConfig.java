package com.costlink.budget.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(
            @Value("${redisson.single-server-config.address:redis://127.0.0.1:6379}") String address,
            @Value("${redisson.single-server-config.database:1}") int database,
            @Value("${redisson.single-server-config.password:}") String password,
            @Value("${redisson.single-server-config.connection-pool-size:16}") int poolSize,
            @Value("${redisson.single-server-config.connection-minimum-idle-size:8}") int minIdle) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(address)
                .setDatabase(database)
                .setConnectionPoolSize(poolSize)
                .setConnectionMinimumIdleSize(minIdle);
        if (password != null && !password.isEmpty()) {
            config.useSingleServer().setPassword(password);
        }
        return Redisson.create(config);
    }
}
