package cn.example.config;

import cn.example.repository.AppUserRepository;
import cn.example.security.PasswordHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
public class TestAccountSeeder {
    @Bean
    @Order(1)
    ApplicationRunner seedTestAccounts(AppUserRepository users, PasswordHasher passwordHasher,
                                       @Value("${app.auth.seed-test-users:true}") boolean seedEnabled,
                                       @Value("${app.auth.initial-password:123456}") String initialPassword) {
        return args -> {
            if (!seedEnabled) return;
            for (String username : new String[]{"admin", "zhangsan"}) {
                if (!users.existsByUsername(username)) {
                    users.insert(username, passwordHasher.encode(initialPassword));
                    System.out.println("已初始化测试页面账号：" + username);
                }
            }
        };
    }
}
