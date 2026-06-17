package com.miniwatson;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// resources folder 의 application.yaml 에서 포트 번호 변경 가능 ( cur :8080)
@SpringBootApplication
public class MiniwatsonApplication {

	public static void main(String[] args) {
		SpringApplication.run(MiniwatsonApplication.class, args);
	}

}
