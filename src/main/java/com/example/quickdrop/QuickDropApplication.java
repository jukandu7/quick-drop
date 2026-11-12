package com.example.quickdrop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class QuickDropApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(QuickDropApplication.class);
		if (Boolean.getBoolean("quickdrop.desktop")) {
			com.example.quickdrop.desktop.DesktopLauncher.configure(application);
		}
		application.run(args);
	}

}
