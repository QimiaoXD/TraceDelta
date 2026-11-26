package io.tracedelta.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminSeedInitializer implements ApplicationRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;

    public AdminSeedInitializer(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            @Value("${tracedelta.admin.password:}") String adminPassword) {
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException("TRACEDELTA_ADMIN_PASSWORD must not be blank");
        }
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        UserEntity existing = userMapper.selectOne(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, "admin"));
        if (existing == null) {
            UserEntity admin = new UserEntity();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setCreatedAt(LocalDateTime.now());
            userMapper.insert(admin);
        }
    }
}
