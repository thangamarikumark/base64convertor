package com.twixor.base64convertor;

import com.twixor.base64convertor.common.config.RuntimeDirectoryBootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Base64convertorApplication {

	public static void main(String[] args) {
		RuntimeDirectoryBootstrap.initializeDirectories();
		SpringApplication.run(Base64convertorApplication.class, args);
        System.out.println("🚀 Base64 Convertor Application started...");
	}

}
