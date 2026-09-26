package com.myapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VectorDbTestApplication {

	public static void main(String[] args) {
		SpringApplication.run(VectorDbTestApplication.class, args);
	}

}
