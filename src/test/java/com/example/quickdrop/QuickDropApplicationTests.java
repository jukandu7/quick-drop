package com.example.quickdrop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties =
		"spring.datasource.url=jdbc:h2:mem:quickdrop-test")
class QuickDropApplicationTests {

	@Test
	void contextLoads() {
	}

}
