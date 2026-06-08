package com.xammer.billops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "spring.session.store-type=none",
    "spring.main.allow-bean-definition-overriding=true"
})
@ActiveProfiles("test")
class BillopsApplicationTests {

	@MockBean
	private RedisConnectionFactory redisConnectionFactory;

	@MockBean
	private StringRedisTemplate stringRedisTemplate;

	@MockBean(name = "redisTemplate")
	private RedisTemplate<Object, Object> redisTemplate;

	@MockBean
	private JavaMailSender javaMailSender;

	@Test
	void contextLoads() {
	}

}
